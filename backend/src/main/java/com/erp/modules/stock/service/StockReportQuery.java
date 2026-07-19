package com.erp.modules.stock.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.dto.StockReportDto;
import com.erp.modules.stock.domain.dto.StockReportRowDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Printable current-stock listing with selling price (SAM Electronix go-live).
 *
 * <p>Mirrors {@link StockValuationQuery}'s per-product aggregate over {@code stock_on_hand}
 * (qty / weighted-avg cost / value), enriched with the company's default price list's base-unit
 * selling price. Distinct from {@code StockValuationQuery} (that one ties out to the GL 1300
 * Inventory balance) — this is a floor/warehouse register, no GL reconciliation.
 *
 * <p>The default price list id is resolved ONCE (a scalar lookup, {@code LIMIT 1} in case more than
 * one row is ever marked default) and joined by a fixed id — not a correlated join on
 * {@code is_default} inline in the aggregate — so a duplicate-default data anomaly can never fan
 * the per-product aggregate out.
 */
@Component
@Transactional(readOnly = true)
public class StockReportQuery {

    private static final String CURRENCY_FALLBACK = "TZS";

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public StockReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    public StockReportDto report(Long companyId) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        CompanyHeader header = loadCompanyHeader(companyId);
        Long defaultPriceListId = resolveDefaultPriceListId(companyId);

        List<Object[]> rawRows = jdbc.query(
                """
                SELECT p.code          AS product_code,
                       p.name          AS product_name,
                       SUM(soh.quantity)                                        AS total_qty,
                       SUM(soh.on_hand_value) / NULLIF(SUM(soh.quantity), 0)   AS avg_cost,
                       SUM(soh.on_hand_value)                                   AS total_value,
                       pp.amount                                                AS selling_price
                FROM stock_on_hand soh
                LEFT JOIN products p ON p.id = soh.product_id
                LEFT JOIN product_prices pp ON pp.product_id = soh.product_id
                       AND pp.price_list_id = ? AND pp.unit_id IS NULL
                WHERE soh.company_id = ?
                GROUP BY p.code, p.name, pp.amount
                ORDER BY p.code NULLS LAST
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("total_qty"),
                        rs.getBigDecimal("avg_cost"),
                        rs.getBigDecimal("total_value"),
                        rs.getBigDecimal("selling_price")
                },
                defaultPriceListId, companyId);

        List<StockReportRowDto> rows = new ArrayList<>(rawRows.size());
        BigDecimal totalValue = BigDecimal.ZERO;
        for (Object[] r : rawRows) {
            BigDecimal value = (BigDecimal) r[4];
            rows.add(new StockReportRowDto(
                    (String) r[0],
                    (String) r[1],
                    (BigDecimal) r[2],
                    (BigDecimal) r[3],
                    (BigDecimal) r[5],
                    value));
            totalValue = totalValue.add(value != null ? value : BigDecimal.ZERO);
        }

        ReportCompanyHeaderDto companyDto = new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());

        return new StockReportDto(
                companyDto,
                header.baseCurrency() != null ? header.baseCurrency() : CURRENCY_FALLBACK,
                rows, totalValue, Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    private Long resolveDefaultPriceListId(Long companyId) {
        return jdbc.query(
                "SELECT id FROM price_lists WHERE company_id = ? AND is_default = true "
                        + "AND status = 'ACTIVE' ORDER BY id LIMIT 1",
                (ResultSetExtractor<Long>) rs -> rs.next() ? rs.getLong("id") : null,
                companyId);
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
                        rs.getString("name"),
                        rs.getString("legal_name"),
                        rs.getString("tax_id"),
                        rs.getString("vrn"),
                        rs.getString("contact_phone"),
                        rs.getString("contact_email"),
                        rs.getString("address_line1"),
                        rs.getString("address_line2"),
                        rs.getString("city"),
                        rs.getString("region"),
                        rs.getString("country"),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    private record CompanyHeader(String name, String legalName, String taxId, String vrn,
                                  String contactPhone, String contactEmail,
                                  String addressLine1, String addressLine2,
                                  String city, String region, String country,
                                  String baseCurrency) {}
}
