package com.erp.modules.stock.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.dto.ReorderReportDto;
import com.erp.modules.stock.domain.dto.ReorderRowDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.BranchReadScope;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reorder Report — every stock line at or below its reorder level, with how far short it is, what
 * to order to bring it back up, and from whom.
 *
 * <p><b>Grain.</b> A reorder level lives on one on-hand line ({@code stock_on_hand}: product ×
 * branch × location), set through {@code PUT /stock/on-hand/uid/{uid}/reorder-level}. A line with
 * no level set is never "below" it and is not listed. Only ACTIVE products are listed — nobody
 * reorders a discontinued item.
 *
 * <p><b>Suggested quantity.</b> When the line also carries a maximum level, the suggestion fills it
 * back up to that maximum; failing that, the product's standard reorder quantity; failing that, the
 * shortfall to the reorder level itself. See {@link #suggestedOrderQty}.
 *
 * <p><b>Which reorder level.</b> The on-hand line's own level — the one the Stock On-Hand screen's
 * low-stock flag and the low-stock notification both read. The product master and product-branch
 * records also carry a reorder level; neither is read here, exactly as neither drives those two,
 * so the three cannot disagree about what is low.
 *
 * <p><b>Cost is a hidden column, not a refused screen.</b> The storekeeper who reorders holds
 * {@code STOCK.VIEW}; buying prices are {@code INVENTORY.VALUATION.VIEW}. Exactly as on the Item
 * Inquiry, the caller's entitlement decides whether the last-cost and order-value figures are read
 * at all, and the response says which happened ({@code costVisible}) so a withheld cost is never
 * mistaken for an uncosted item. When hidden, the cost subquery is not even run.
 *
 * <p><b>Last cost</b> is the unit cost of the product's most recent goods receipt anywhere in the
 * company — what was last paid for it, which is the figure a buyer quotes against.
 */
@Component
@Transactional(readOnly = true)
public class ReorderReportQuery {

    private static final String CURRENCY_FALLBACK = "TZS";
    private static final int MONEY_SCALE = 2;

    private final JdbcTemplate    jdbc;
    private final ScopeGuard      scopeGuard;
    private final BranchReadGuard branchGuard;

    public ReorderReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard,
                              BranchReadGuard branchGuard) {
        this.jdbc        = jdbc;
        this.scopeGuard  = scopeGuard;
        this.branchGuard = branchGuard;
    }

    /**
     * @param branchUid   optional; null lists every branch
     * @param supplierUid optional; narrows to products whose PREFERRED supplier is this one
     * @param includeCost whether the caller may see buying prices
     */
    public ReorderReportDto report(Long companyId, String branchUid, String supplierUid,
                                   boolean includeCost) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        Company company = loadCompany(companyId);
        NamedRef branch = resolve("branches", "name", branchUid, companyId, "Branch");
        BranchReadScope scope = branchGuard.readScope(principal, companyId, branch != null ? branch.id() : null);
        NamedRef supplier = resolve("suppliers", "display_name", supplierUid, companyId, "Supplier");

        List<ReorderRowDto> rows = queryRows(companyId, branch, supplier, includeCost, scope);

        BigDecimal orderValue = BigDecimal.ZERO;
        int withoutCost = 0;
        for (ReorderRowDto r : rows) {
            if (r.estimatedOrderValue() != null) {
                orderValue = orderValue.add(r.estimatedOrderValue());
            } else {
                withoutCost++;
            }
        }

        return new ReorderReportDto(
                company.header(),
                branch != null ? branch.name() : null,
                supplier != null ? supplier.name() : null,
                company.baseCurrency() != null ? company.baseCurrency() : CURRENCY_FALLBACK,
                includeCost,
                rows,
                rows.size(),
                includeCost ? orderValue : null,
                includeCost ? withoutCost : 0,
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    private List<ReorderRowDto> queryRows(Long companyId, NamedRef branch, NamedRef supplier,
                                          boolean includeCost, BranchReadScope scope) {
        List<Object> params = new ArrayList<>();
        params.add(companyId);
        StringBuilder filter = new StringBuilder(scope.sql("soh.branch_id"));
        if (branch != null) {
            filter.append(" AND soh.branch_id = ?");
            params.add(branch.id());
        }
        if (supplier != null) {
            filter.append(" AND p.preferred_supplier_id = ?");
            params.add(supplier.id());
        }

        // The cost lookup is a fixed literal chosen by the entitlement, never caller text.
        String costSelect = includeCost ? "lc.unit_cost_amount" : "CAST(NULL AS NUMERIC)";
        String costJoin = includeCost
                ? """
                  LEFT JOIN LATERAL (
                      SELECT sm.unit_cost_amount
                      FROM stock_movements sm
                      WHERE sm.company_id = soh.company_id
                        AND sm.product_id = soh.product_id
                        AND sm.movement_type = 'GOODS_RECEIPT'
                        AND sm.unit_cost_amount IS NOT NULL
                      ORDER BY sm.occurred_at DESC, sm.id DESC
                      LIMIT 1
                  ) lc ON true
                  """
                : "";

        String sql = """
                SELECT p.uid              AS product_uid,
                       p.code             AS product_code,
                       p.name             AS product_name,
                       u.name             AS unit_name,
                       b.name             AS branch_name,
                       loc.code           AS location_code,
                       loc.name           AS location_name,
                       soh.quantity       AS on_hand,
                       COALESCE(soh.reorder_level, pb.reorder_level, p.reorder_level) AS reorder_level,
                       soh.max_qty        AS max_qty,
                       p.reorder_qty      AS reorder_qty,
                       sup.uid            AS supplier_uid,
                       sup.display_name   AS supplier_name,
                """ + "       " + costSelect + " AS last_cost\n" + """
                FROM stock_on_hand soh
                JOIN products p          ON p.id = soh.product_id AND p.company_id = soh.company_id
                JOIN branches b          ON b.id = soh.branch_id
                JOIN stock_locations loc ON loc.id = soh.location_id
                LEFT JOIN product_branch pb ON pb.product_id = soh.product_id
                                           AND pb.branch_id = soh.branch_id
                LEFT JOIN units_of_measure u ON u.id = p.base_unit_id
                LEFT JOIN suppliers sup  ON sup.id = p.preferred_supplier_id
                                        AND sup.company_id = p.company_id
                """ + costJoin + """
                WHERE soh.company_id = ?
                  -- STK-10 / LBO-16: the row's own level, else the product's level for the branch,
                  -- else the Product Master level. An INHERITED level applies only where stock
                  -- sits or at the default location (a zero leftover row at In-Transit is not
                  -- "out of stock"). Same rule as the on-hand Low flag and the LOW_STOCK alert.
                  AND COALESCE(soh.reorder_level, pb.reorder_level, p.reorder_level) IS NOT NULL
                  AND soh.quantity <= COALESCE(soh.reorder_level, pb.reorder_level, p.reorder_level)
                  AND (soh.reorder_level IS NOT NULL OR soh.quantity <> 0 OR loc.is_default)
                  AND p.status = 'ACTIVE'
                """ + filter + """

                ORDER BY sup.display_name NULLS LAST, p.code, b.name, loc.code
                """;

        return jdbc.query(sql, (rs, rowNum) -> {
            BigDecimal onHand  = rs.getBigDecimal("on_hand");
            BigDecimal level   = rs.getBigDecimal("reorder_level");
            BigDecimal max     = rs.getBigDecimal("max_qty");
            BigDecimal lastCost = rs.getBigDecimal("last_cost");
            BigDecimal shortfall = level.subtract(onHand);
            BigDecimal suggested = suggestedOrderQty(onHand, shortfall, max,
                    rs.getBigDecimal("reorder_qty"));
            BigDecimal value = lastCost != null
                    ? suggested.multiply(lastCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
                    : null;
            return new ReorderRowDto(
                    rs.getString("product_uid"),
                    rs.getString("product_code"),
                    rs.getString("product_name"),
                    rs.getString("unit_name"),
                    rs.getString("branch_name"),
                    rs.getString("location_code"),
                    rs.getString("location_name"),
                    onHand,
                    level,
                    max,
                    shortfall,
                    suggested,
                    rs.getString("supplier_uid"),
                    rs.getString("supplier_name"),
                    lastCost,
                    value);
        }, params.toArray());
    }

    /**
     * What to order, in order of preference: fill the line back up to its maximum level when one is
     * set; otherwise the product's standard reorder quantity (its usual order size) when one is set;
     * otherwise just the shortfall. Never less than the shortfall, so a maximum or order size set
     * too small cannot suggest an order that leaves the line still at the trigger.
     *
     * <p>Package-private and static so the rule is testable without a database.
     */
    static BigDecimal suggestedOrderQty(BigDecimal onHand, BigDecimal shortfall, BigDecimal max,
                                        BigDecimal productReorderQty) {
        if (max != null) {
            return max.subtract(onHand).max(shortfall);
        }
        if (productReorderQty != null && productReorderQty.signum() > 0) {
            return productReorderQty.max(shortfall);
        }
        return shortfall;
    }

    private NamedRef resolve(String table, String nameColumn, String uid, Long companyId,
                             String entityName) {
        if (uid == null || uid.isBlank()) {
            return null;
        }
        List<NamedRef> found = jdbc.query(
                "SELECT id, " + nameColumn + " AS name FROM " + table
                        + " WHERE uid = ? AND company_id = ?",
                (rs, rowNum) -> new NamedRef(rs.getLong("id"), rs.getString("name")),
                uid, companyId);
        if (found.isEmpty()) {
            throw NotFoundException.of(entityName, uid);
        }
        return found.get(0);
    }

    private Company loadCompany(Long companyId) {
        List<Company> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, base_currency
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new Company(
                        new ReportCompanyHeaderDto(
                                rs.getString("name"),
                                rs.getString("legal_name"),
                                rs.getString("address_line1"),
                                rs.getString("address_line2"),
                                rs.getString("city"),
                                rs.getString("region"),
                                rs.getString("country"),
                                rs.getString("contact_phone"),
                                rs.getString("contact_email"),
                                rs.getString("tax_id"),
                                rs.getString("vrn")),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    private record NamedRef(Long id, String name) {}

    private record Company(ReportCompanyHeaderDto header, String baseCurrency) {}
}
