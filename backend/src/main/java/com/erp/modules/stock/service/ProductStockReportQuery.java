package com.erp.modules.stock.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.dto.ProductStockReportDto;
import com.erp.modules.stock.domain.dto.ProductStockRowDto;
import com.erp.modules.stock.domain.dto.ProductStockTotalsDto;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backs the two product-stock registers the shop floor actually asks for:
 *
 * <ul>
 *   <li><b>Product List</b> — code, description, on-hand, buying price, selling price and supplier.
 *       A reference listing, deliberately with no grand total: summing unrelated products tells the
 *       reader nothing.</li>
 *   <li><b>Stock Value</b> — the same rows plus cost value and sale value per item, optionally
 *       narrowed to one supplier, with grand totals at the foot.</li>
 * </ul>
 *
 * <p>Both are driven from {@code products}, not from {@code stock_on_hand}, so a product that has
 * never been stocked still appears (at zero) instead of vanishing from its own catalogue.
 *
 * <p><b>Row set differs by report, on purpose.</b> The Product List is a catalogue: ACTIVE, stockable
 * products only. The Stock Value report additionally pulls in any product still HOLDING stock even if
 * it has since been archived or made non-stockable — otherwise its cost total would silently disagree
 * with {@link StockValuationQuery}, which applies no status filter and is the figure reconciled to GL
 * Inventory 1300. Two different "stock values" for one company on one day is how an argument starts.
 * The count of such rows is carried on the totals so the page can say so out loud.
 *
 * <p><b>Buying price</b> is the IMPLIED unit cost — {@code SUM(on_hand_value) / SUM(quantity)} over
 * the rows in scope — exactly as {@link StockValuationQuery} and {@code StockReportQuery} derive it.
 * It must be derived rather than read from a single {@code stock_on_hand.avg_cost}, because the
 * per-location avg_costs are NOT guaranteed to agree: the receipt family recomputes and syncs one
 * figure across every location row of a (company, product), but an opening valuation writes to the
 * ONE row it was given, and the stock import drives that per active branch. Two branches then
 * legitimately carry two different avg_costs, and picking one of them (the max, say) prints a unit
 * price that does not reconcile with the Cost Value beside it — 10 @ 1,000 plus 10 @ 1,200 would read
 * "Qty 20, Buying 1,200.00, Cost Value 22,000.00", and the reader is left doing arithmetic that does
 * not work. The implied average always satisfies {@code qty x buying = cost value}, which is the only
 * property a reader actually checks, and it keeps this report and the GL-reconciled valuation telling
 * the same story about the same stock on the same day.
 *
 * <p>{@code MAX(avg_cost)} survives only as the FALLBACK for the zero-quantity case, where there is
 * nothing to divide by: an out-of-stock-but-previously-costed catalogue item still has a known last
 * cost, and blanking it (which a bare division would do) loses real information on what is, for a
 * catalogue, a common row rather than an edge case. A product nobody has ever costed stays blank —
 * see the {@code valued} guard in the row mapper, since an unvalued row carries {@code on_hand_value}
 * 0 and would otherwise divide out to a confident 0.00.
 *
 * <p>One consequence to state on screen: on a branch-filtered run the cost is that branch's QUANTITY
 * valued at that branch's own on-hand value, and the company-wide run averages across branches — so
 * the column is "cost value", never "this branch's cost".
 *
 * <p><b>Selling price.</b> Taken from the company's default price list. The base-unit row
 * ({@code unit_id IS NULL}, ADR-0048 D-1) wins; when a product is priced only by the pack — a crate
 * of spirits, say — the pack price is divided by its {@code factor_to_base} so the column still
 * shows a per-unit figure rather than a blank. Where several packs are priced, the lowest per-unit
 * equivalent is used, which keeps the sale value conservative. This resolves in ONE pass in the CTE
 * below; calling the price-resolution service per row would be a query per product.
 *
 * <p>Only prices in the company's base currency are read. {@code product_prices.currency} is free to
 * differ from it, and adding a USD figure into a TZS grand total would be worse than a blank: the
 * blank is disclosed by {@code unpricedItems}, a mixed total is invisible.
 *
 * <p><b>VAT is not applied here.</b> The list's {@code price_includes_vat} stance travels on the DTO
 * so the screen can label the column honestly. Grossing a net price up silently would produce a
 * shelf price the till never charges.
 *
 * <p>Cross-module reads (products / suppliers / price lists / goods receipts) are scalar native-SQL
 * joins, the same pattern as {@link StockValuationQuery} — no entity or service is imported across
 * the boundary.
 */
@Component
@Transactional(readOnly = true)
public class ProductStockReportQuery {

    private static final String CURRENCY_FALLBACK = "TZS";

    /**
     * Scale of the derived unit cost. {@code stock_on_hand.avg_cost} is stored at 4 dp, so 6 keeps
     * the derived figure at least as precise as the stored one it replaces; both are displayed at
     * 2 dp, so this only governs how the division rounds, never what the reader sees.
     */
    private static final int IMPLIED_COST_SCALE = 6;

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public ProductStockReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    /**
     * @param branchUid   optional; null covers every branch in the company
     * @param supplierUid optional; null covers every supplier
     * @param withTotals  true for the Stock Value report, false for the plain Product List
     */
    public ProductStockReportDto report(Long companyId, String branchUid, String supplierUid,
                                         boolean withTotals) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        CompanyHeader header = loadCompanyHeader(companyId);
        NamedRef branch   = resolveNamedRef("branches",  "name",         branchUid,   companyId, "Branch");
        assertMayReadBranch(principal, branch);
        NamedRef supplier = resolveNamedRef("suppliers", "display_name", supplierUid, companyId, "Supplier");
        PriceList priceList = resolveDefaultPriceList(companyId);

        String currency = header.baseCurrency() != null ? header.baseCurrency() : CURRENCY_FALLBACK;

        List<ProductStockRowDto> rows = queryRows(
                companyId,
                priceList != null ? priceList.id() : null,
                currency,
                branch != null ? branch.id() : null,
                supplier != null ? supplier.id() : null,
                withTotals);

        ReportCompanyHeaderDto companyDto = new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());

        return new ProductStockReportDto(
                companyDto,
                branch != null ? branch.name() : null,
                supplier != null ? supplier.name() : null,
                currency,
                priceList != null ? priceList.name() : null,
                priceList != null && priceList.includesVat(),
                rows,
                withTotals ? totalsOf(rows) : null,
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    /**
     * @param valuationRowSet true for Stock Value — keep discontinued products that still hold stock,
     *                        so the foot of the report ties to the GL-reconciled valuation
     */
    private List<ProductStockRowDto> queryRows(Long companyId, Long priceListId, String currency,
                                                Long branchId, Long supplierId,
                                                boolean valuationRowSet) {
        // Bound in textual order of the '?' placeholders below — sell, soh, last_supplier, products.
        List<Object> params = new ArrayList<>();
        params.add(companyId);          // sell CTE
        params.add(priceListId);        // sell CTE (nullable — no default list means no prices)
        params.add(currency);           // sell CTE — never mix currencies into one total
        params.add(companyId);          // soh CTE
        params.add(branchId);           // soh CTE branch predicate
        params.add(branchId);
        params.add(companyId);          // last_supplier CTE
        params.add(companyId);          // products

        // Catalogue vs valuation: see the class javadoc. Written against the LEFT JOINed soh, so a
        // product with no stock_on_hand row at all reads as qty 0 and is excluded from the second arm.
        String rowSetSql = valuationRowSet
                ? "\n                  AND ((p.status = 'ACTIVE' AND p.stockable = true)"
                        + "\n                       OR COALESCE(soh.qty, 0) <> 0)"
                : "\n                  AND p.status = 'ACTIVE'"
                        + "\n                  AND p.stockable = true";

        String supplierSql = "";
        if (supplierId != null) {
            supplierSql = "\n                  AND COALESCE(p.preferred_supplier_id, ls.supplier_id) = ?";
            params.add(supplierId);
        }

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
                           -- Fallback only, for the qty = 0 case; the buying price itself is the
                           -- implied average val/qty, derived in the row mapper. See the class doc.
                           MAX(avg_cost)      AS stored_avg_cost,
                           COUNT(*) FILTER (WHERE avg_cost IS NOT NULL) AS valued_rows
                    FROM stock_on_hand
                    WHERE company_id = ?
                      AND (CAST(? AS BIGINT) IS NULL OR branch_id = CAST(? AS BIGINT))
                    GROUP BY product_id
                ),
                last_supplier AS (
                    SELECT DISTINCT ON (grl.product_id)
                           grl.product_id,
                           gr.supplier_id
                    FROM goods_receipt_lines grl
                    JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id
                    WHERE grl.company_id = ?
                      AND gr.status = 'RECEIVED'
                    ORDER BY grl.product_id, gr.received_at DESC NULLS LAST, grl.id DESC
                )
                SELECT p.code                       AS product_code,
                       p.name                       AS product_name,
                       s.display_name               AS supplier_name,
                       COALESCE(soh.qty, 0)         AS qty,
                       soh.val                      AS cost_value,
                       soh.stored_avg_cost          AS stored_avg_cost,
                       soh.valued_rows              AS valued_rows,
                       COALESCE(sell.base_price, sell.pack_unit_price) AS selling_price,
                       (p.status = 'ACTIVE' AND p.stockable)           AS in_catalogue
                FROM products p
                LEFT JOIN soh           ON soh.product_id  = p.id
                LEFT JOIN sell          ON sell.product_id = p.id
                LEFT JOIN last_supplier ls ON ls.product_id = p.id
                LEFT JOIN suppliers s ON s.id = COALESCE(p.preferred_supplier_id, ls.supplier_id)
                                     AND s.company_id = p.company_id
                WHERE p.company_id = ?
                """ + rowSetSql + supplierSql + """

                ORDER BY s.display_name NULLS LAST, p.code
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal qty       = rs.getBigDecimal("qty");
            BigDecimal costValue = rs.getBigDecimal("cost_value");
            int valuedRows       = rs.getInt("valued_rows");
            BigDecimal selling   = rs.getBigDecimal("selling_price");

            // Unvalued stock carries on_hand_value 0 with a NULL avg_cost. Reporting a 0 cost would
            // read as "this stock is free"; blank says "nobody has costed it", which is the truth.
            // The same guard has to sit in front of the implied average, or 0 / 10 units would print
            // a confident 0.00 unit cost for stock nobody has ever valued.
            boolean valued = valuedRows > 0;

            return new ProductStockRowDto(
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("supplier_name"),
                    qty != null ? qty : BigDecimal.ZERO,
                    valued ? buyingPrice(costValue, qty, rs.getBigDecimal("stored_avg_cost")) : null,
                    valued ? costValue : null,
                    selling,
                    selling != null && qty != null ? qty.multiply(selling) : null,
                    !rs.getBoolean("in_catalogue"));
        }, params.toArray());
    }

    /**
     * The Buying Price cell: the implied unit cost of the stock in scope,
     * {@code onHandValue / quantity}, with the stored per-location {@code avg_cost} as the fallback
     * when there is no quantity to divide by.
     *
     * <p>Deriving it is what keeps the line internally consistent — {@code quantity x buyingPrice}
     * equals the Cost Value printed beside it, for one branch and for many. Reading a single stored
     * avg_cost instead breaks that as soon as two branches carry different costs for one product,
     * which an opening valuation or a per-branch stock import produces as a matter of course.
     *
     * <p>The fallback is deliberately NOT a blank: a catalogue row that has sold out still has a
     * known last cost, and that is worth printing. It is only reached at zero quantity, so it can
     * never contradict a Cost Value.
     *
     * <p>The caller must first establish that SOMETHING has been costed (the {@code valued} guard);
     * unvalued stock carries {@code on_hand_value} 0, which would otherwise divide out to 0.00 and
     * read as "free".
     *
     * <p>Package-private and static so the arithmetic is exercised without a database.
     */
    static BigDecimal buyingPrice(BigDecimal onHandValue, BigDecimal quantity,
                                   BigDecimal storedAvgCost) {
        if (onHandValue != null && quantity != null && quantity.signum() != 0) {
            return onHandValue.divide(quantity, IMPLIED_COST_SCALE, RoundingMode.HALF_UP);
        }
        return storedAvgCost;
    }

    /**
     * Sums what is genuinely known, and counts what is not, so the foot of the report cannot lie.
     *
     * <p>Package-private and static so the arithmetic can be exercised without a database — the
     * "unpriced is not free" rule is the whole point of the counters and deserves a fast test.
     */
    static ProductStockTotalsDto totalsOf(List<ProductStockRowDto> rows) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal sale = BigDecimal.ZERO;
        int unvalued = 0;
        int unpriced = 0;
        int discontinued = 0;
        for (ProductStockRowDto r : rows) {
            qty = qty.add(r.quantityOnHand());
            if (r.costValue() != null) {
                cost = cost.add(r.costValue());
            } else if (r.quantityOnHand().signum() != 0) {
                unvalued++;
            }
            if (r.saleValue() != null) {
                sale = sale.add(r.saleValue());
            } else if (r.quantityOnHand().signum() != 0) {
                unpriced++;
            }
            if (r.discontinued()) {
                discontinued++;
            }
        }
        return new ProductStockTotalsDto(
                qty, cost, sale, sale.subtract(cost), unvalued, unpriced, discontinued);
    }

    /**
     * The company's default price list, with the same fallback chain the Product Master screen uses
     * (flagged default → a list coded DEFAULT/STANDARD → the only ACTIVE list).
     *
     * <p>Nothing in the system ever SETS {@code is_default} — there is no seeder, no auto-set and no
     * unique index behind the flag — so a strict {@code is_default = true} lookup returns nothing for
     * most companies and the report prints blank prices and a 0.00 sale total on a shop whose till is
     * charging perfectly well. Matching the pricing screen's resolution order means the report and the
     * screen can never tell different stories about which list is "the" list.
     *
     * <p>Several ACTIVE lists with none flagged and none named DEFAULT/STANDARD still returns null —
     * correct: nobody has decided, and the header says so rather than guessing. The ORDER BY also
     * makes the duplicate-default anomaly deterministic instead of oldest-wins-by-accident.
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

    /**
     * A branch uid on the query string is a filter the CALLER supplies, so it must clear the same bar
     * as the {@code X-Branch-Uid} session override in {@code JwtRequestContextFilter} (ADR-0003):
     * same company — already enforced by {@link #resolveNamedRef}, which resolves uid and company_id
     * together — AND a live {@code user_branch} assignment. Without this a storekeeper assigned only
     * to Arusha could read Dodoma's quantities, costs and margins by editing the URL, which the
     * header path refuses. Scoped from the RESOLVED branch, never from the raw parameter.
     *
     * <p>Root is exempt (ScopeGuard already audits its cross-scope reads). Anything else fails closed.
     *
     * <p>This is the STRICTER of the two paths, not a mirror of it: the header path checks only that
     * an assignment row exists, while this one additionally requires it to still be live (not
     * deactivated, not revoked). So a revoked user can still switch session scope by header yet is
     * refused the branch-filtered report. Narrowing the header path is an IAM change and would move
     * the shell's branch switcher with it — see {@link #branchNotAssigned()} on why the wording here
     * stays about the assignment rather than about permissions.
     *
     * <p>Read with raw SQL rather than IAM's {@code UserBranchRepository}: this class is scalar-SQL
     * throughout and importing another module's repository would breach the module boundary.
     */
    private void assertMayReadBranch(RequestContext.Principal principal, NamedRef branch) {
        if (branch == null) {
            return; // no branch filter — the company-wide read is already authorised
        }
        if (principal != null && principal.root()) {
            return;
        }
        Long userId = principal != null ? principal.userId() : null;
        if (userId == null) {
            // A session with no user behind it is a broken session, not a branch problem — the
            // generic wording is the honest one here.
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
            throw branchNotAssigned();
        }
    }

    /**
     * What the caller reads when they filter to a branch they are not assigned to.
     *
     * <p>Deliberately NOT the generic "you do not have permission" wording. The caller holds the
     * report permission — that is how they reached the screen — so a permission-shaped refusal sends
     * them to an administrator to ask for something they already have, and leaves them believing the
     * report is broken. The actual remedy is either a branch assignment or no branch filter, and both
     * are worth saying. Naming the constraint that was applied is not a leak: it discloses nothing
     * about the branch, its data, or anyone else's access.
     *
     * <p>Plain literal on purpose — the branch name and uid stay out of it (error-message hygiene).
     */
    static ForbiddenException branchNotAssigned() {
        return new ForbiddenException(
                "You are not assigned to that branch. Choose a branch you work in, or clear the "
                        + "branch filter to see the whole company.");
    }

    /** Resolve an optional uid filter to (id, name), scoped to the caller's company. */
    private NamedRef resolveNamedRef(String table, String nameColumn, String uid,
                                      Long companyId, String entityName) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        String sql = "SELECT id, " + nameColumn + " AS name FROM " + table
                + " WHERE uid = ? AND company_id = ?";
        List<NamedRef> found = jdbc.query(sql,
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of(entityName, uid);
        }
        return found.get(0);
    }

    private CompanyHeader loadCompanyHeader(Long companyId) {
        List<CompanyHeader> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new CompanyHeader(
                        rs.getString("name"), rs.getString("legal_name"),
                        rs.getString("tax_id"), rs.getString("vrn"),
                        rs.getString("contact_phone"), rs.getString("contact_email"),
                        rs.getString("address_line1"), rs.getString("address_line2"),
                        rs.getString("city"), rs.getString("region"), rs.getString("country"),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    // -------------------------------------------------------------------------

    private record NamedRef(Long id, String name) {}

    private record PriceList(Long id, String name, boolean includesVat) {}

    private record CompanyHeader(String name, String legalName, String taxId, String vrn,
                                  String contactPhone, String contactEmail,
                                  String addressLine1, String addressLine2,
                                  String city, String region, String country,
                                  String baseCurrency) {}
}
