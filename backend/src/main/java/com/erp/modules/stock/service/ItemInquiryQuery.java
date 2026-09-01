package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.ItemInquiryDto;
import com.erp.modules.stock.domain.dto.ItemInquiryRowDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counter lookup: one search box, one row per matching item, carrying code, description, cost,
 * selling price and what is left on the shelf (K-2026-08-30 #3).
 *
 * <p><b>Why this is not the Product List report.</b> Every field here already exists on
 * {@link ProductStockReportQuery}, but that is a whole-catalogue register: it returns every product
 * in the company, unfiltered by any search, and is gated on {@code INVENTORY.VALUATION.VIEW}
 * because it discloses buying prices in bulk. The person who needs to answer "how many of these are
 * left and what do we sell it for" is standing at a counter with a customer in front of them. This
 * query is that question — a search, a small bounded result, and cost shown only to callers who may
 * see cost.
 *
 * <p><b>Derivations are deliberately shared</b> with the register rather than re-derived: the
 * selling-price resolution (base-unit row wins, else the cheapest per-unit-equivalent pack price)
 * and the implied buying price ({@code SUM(on_hand_value) / SUM(quantity)}, falling back to the
 * stored per-location {@code avg_cost} only at zero quantity) both come from there, the second by
 * calling {@link ProductStockReportQuery#buyingPrice} directly. Two screens quoting two different
 * costs for one item on one day is how an argument starts.
 *
 * <p><b>Cost visibility.</b> {@code ProductStockReportController} states the standing rule: "a
 * cashier who may look up a product must not thereby learn its margin". That rule is kept — but as
 * a hidden COLUMN rather than a refused screen, because refusing the whole lookup would take the
 * item search away from exactly the people serving customers with it. The caller decides via
 * {@code includeCost}, and the response says which of the two it did (see
 * {@link ItemInquiryDto#costVisible}) so a hidden cost never reads as an uncosted item.
 *
 * <p>Cross-module reads (products, prices, barcodes) are scalar native-SQL joins, the same pattern
 * as {@link ProductStockReportQuery} — no entity or service crosses the module boundary.
 */
@Component
@Transactional(readOnly = true)
public class ItemInquiryQuery {

    private static final String CURRENCY_FALLBACK = "TZS";

    /**
     * How many matches come back at most.
     *
     * <p>A counter lookup is a search, not a listing: past a screenful the answer is "type more of
     * the name", not another hundred rows. The response says when it clipped, so the reader is never
     * left believing a truncated list is the whole answer — the trap that
     * {@code picker-seed-vs-server-search} cost a day of debugging on the product pickers.
     */
    private static final int MAX_ROWS = 50;

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public ItemInquiryQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    /**
     * @param search      code, description fragment, or a full barcode; required — this answers a
     *                    question about an item, it does not list the catalogue
     * @param branchUid   optional; null sums every branch in the company
     * @param includeCost whether the caller may see buying prices
     */
    public ItemInquiryDto inquire(Long companyId, String search, String branchUid,
                                   boolean includeCost) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        CompanyRef company = loadCompany(companyId);
        NamedRef branch = resolveBranch(branchUid, companyId);
        assertMayReadBranch(principal, branch);
        PriceList priceList = resolveDefaultPriceList(companyId);

        String currency = company.baseCurrency() != null ? company.baseCurrency() : CURRENCY_FALLBACK;
        String term = search != null ? search.trim() : "";

        // An empty search is not "everything": it is a question nobody asked. Answering it with the
        // whole catalogue would turn a counter lookup into an unbounded register read.
        List<ItemInquiryRowDto> rows = term.isEmpty()
                ? List.of()
                : queryRows(companyId, priceList != null ? priceList.id() : null, currency,
                            branch != null ? branch.id() : null, term, includeCost);

        boolean truncated = rows.size() > MAX_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_ROWS);
        }

        return new ItemInquiryDto(
                branch != null ? branch.name() : null,
                currency,
                priceList != null ? priceList.name() : null,
                priceList != null && priceList.includesVat(),
                includeCost,
                truncated,
                rows);
    }

    // -------------------------------------------------------------------------

    private List<ItemInquiryRowDto> queryRows(Long companyId, Long priceListId, String currency,
                                               Long branchId, String term, boolean includeCost) {
        String like = "%" + term.toLowerCase() + "%";

        List<Object> params = new ArrayList<>();
        params.add(companyId);      // sell CTE
        params.add(priceListId);    // sell CTE (nullable — no default list means no prices)
        params.add(currency);       // sell CTE — never mix currencies
        params.add(companyId);      // soh CTE
        params.add(branchId);       // soh CTE branch predicate
        params.add(branchId);
        params.add(companyId);      // products
        params.add(like);           // code
        params.add(like);           // name
        params.add(term);           // barcode — exact, a scan is not a fragment
        params.add(MAX_ROWS + 1);   // one over, so "there are more" is known rather than guessed

        String sql = """
                WITH sell AS (
                    SELECT pp.product_id,
                           MIN(CASE WHEN pp.unit_id IS NULL THEN pp.amount END) AS base_price,
                           MIN(CASE WHEN pp.unit_id IS NOT NULL AND bp.factor_to_base > 0
                                    THEN pp.amount / bp.factor_to_base END)     AS pack_unit_price
                    FROM product_prices pp
                    LEFT JOIN product_bulk_packs bp
                           ON bp.product_id = pp.product_id AND bp.unit_id = pp.unit_id
                    WHERE pp.company_id = ?
                      AND pp.price_list_id = CAST(? AS BIGINT)
                      AND pp.currency = ?
                    GROUP BY pp.product_id
                ),
                soh AS (
                    SELECT product_id,
                           SUM(quantity)      AS qty,
                           SUM(on_hand_value) AS val,
                           MAX(avg_cost)      AS stored_avg_cost,
                           COUNT(*) FILTER (WHERE avg_cost IS NOT NULL) AS valued_rows
                    FROM stock_on_hand
                    WHERE company_id = ?
                      AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                    GROUP BY product_id
                )
                SELECT p.uid                        AS product_uid,
                       p.code                       AS product_code,
                       p.name                       AS product_name,
                       p.stockable                  AS stockable,
                       u.name                       AS unit_name,
                       COALESCE(soh.qty, 0)         AS qty,
                       soh.val                      AS cost_value,
                       soh.stored_avg_cost          AS stored_avg_cost,
                       soh.valued_rows              AS valued_rows,
                       COALESCE(sell.base_price, sell.pack_unit_price) AS selling_price
                FROM products p
                LEFT JOIN soh  ON soh.product_id  = p.id
                LEFT JOIN sell ON sell.product_id = p.id
                LEFT JOIN units_of_measure u ON u.id = p.base_unit_id
                WHERE p.company_id = ?
                  AND p.status = 'ACTIVE'
                  AND (lower(p.code) LIKE ?
                       OR lower(p.name) LIKE ?
                       OR EXISTS (SELECT 1 FROM product_barcodes b
                                  WHERE b.product_id = p.id AND b.barcode = ?))
                ORDER BY p.code
                LIMIT ?
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal qty       = rs.getBigDecimal("qty");
            BigDecimal costValue = rs.getBigDecimal("cost_value");
            boolean    valued    = rs.getInt("valued_rows") > 0;

            // Same guard as the register: unvalued stock carries on_hand_value 0, and dividing that
            // out would print a confident 0.00 for stock nobody has ever costed.
            BigDecimal buying = includeCost && valued
                    ? ProductStockReportQuery.buyingPrice(
                            costValue, qty, rs.getBigDecimal("stored_avg_cost"))
                    : null;

            return new ItemInquiryRowDto(
                    rs.getString("product_uid"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("unit_name"),
                    qty != null ? qty : BigDecimal.ZERO,
                    rs.getBoolean("stockable"),
                    buying,
                    rs.getBigDecimal("selling_price"));
        }, params.toArray());
    }

    /**
     * The company's default price list, resolved exactly as {@link ProductStockReportQuery} does
     * (flagged default → a list coded DEFAULT/STANDARD → the only ACTIVE list). Nothing in the
     * system ever sets {@code is_default}, so a strict lookup would blank the selling price on most
     * companies — including shops whose till is charging perfectly well.
     */
    private PriceList resolveDefaultPriceList(Long companyId) {
        return jdbc.query(
                """
                WITH active AS (
                    SELECT id, name, code, price_includes_vat, is_default,
                           COUNT(*) OVER () AS n
                    FROM price_lists
                    WHERE company_id = ? AND status = 'ACTIVE'
                )
                SELECT id, name, price_includes_vat
                FROM active
                WHERE is_default
                   OR upper(code) IN ('DEFAULT', 'STANDARD')
                   OR n = 1
                ORDER BY is_default DESC,
                         CASE WHEN upper(code) IN ('DEFAULT', 'STANDARD') THEN 0 ELSE 1 END,
                         id
                LIMIT 1
                """,
                (ResultSetExtractor<PriceList>) rs -> rs.next()
                        ? new PriceList(rs.getLong("id"), rs.getString("name"),
                                        rs.getBoolean("price_includes_vat"))
                        : null,
                companyId);
    }

    private NamedRef resolveBranch(String branchUid, Long companyId) {
        if (branchUid == null || branchUid.isBlank()) {
            return null;
        }
        List<NamedRef> found = jdbc.query(
                "SELECT id, name FROM branches WHERE uid = ? AND company_id = ?",
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                branchUid, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of("Branch", branchUid);
        }
        return found.get(0);
    }

    /**
     * A branch uid on the query string is a filter the CALLER supplies, so it clears the same bar as
     * the {@code X-Branch-Uid} session override: same company (already enforced by the resolve
     * above) AND a live {@code user_branch} assignment. Identical to the register's rule — see
     * {@code ProductStockReportQuery#assertMayReadBranch} — because the hole would be identical too:
     * without it, a storekeeper assigned only to Arusha could read Dodoma's quantities by editing a
     * URL, which the header path refuses.
     */
    private void assertMayReadBranch(RequestContext.Principal principal, NamedRef branch) {
        if (branch == null || (principal != null && principal.root())) {
            return;
        }
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            throw ForbiddenException.notPermitted();
        }
        Integer assigned = jdbc.query(
                """
                SELECT 1
                FROM user_branch
                WHERE user_id = ? AND branch_id = ? AND active = true AND revoked_at IS NULL
                LIMIT 1
                """,
                (ResultSetExtractor<Integer>) rs -> rs.next() ? 1 : null,
                userId, branch.id());
        if (assigned == null) {
            throw ProductStockReportQuery.branchNotAssigned();
        }
    }

    private CompanyRef loadCompany(Long companyId) {
        List<CompanyRef> found = jdbc.query(
                "SELECT name, base_currency FROM companies WHERE id = ?",
                (rs, rowNum) -> new CompanyRef(rs.getString("name"), rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    // -------------------------------------------------------------------------

    private record NamedRef(Long id, String name) {}

    private record PriceList(Long id, String name, boolean includesVat) {}

    private record CompanyRef(String name, String baseCurrency) {}
}
