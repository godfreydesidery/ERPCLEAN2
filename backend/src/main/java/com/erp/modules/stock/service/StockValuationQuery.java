package com.erp.modules.stock.service;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.dto.StockValuationReconDto;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.domain.dto.StockValuationRowDto;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock valuation report + GL reconciliation bar (ADR-0020 D-6, FR-INV-07).
 *
 * <p>Aggregates {@code on_hand_value} per product across all branches for a company (single-location
 * v1), enriches with product code/name via a native SQL join (scalar cross-module read — same
 * pattern as {@code BillMatchServiceImpl} JDBC reads, ADR boundary rule: no entity import across
 * modules), and reconciles the total to the GL 1300 Inventory account balance (BR-INV-06).
 *
 * <p>{@link JournalLineRepository#accountBalance} provides the expected GL side — same cross-module
 * leaf-reader pattern as {@code CashGlReconciliationQuery} (ADR-0018 D-12 allowance).
 */
@Component
@Transactional(readOnly = true)
public class StockValuationQuery {

    private static final String CURRENCY = "TZS";

    private final JdbcTemplate          jdbc;
    private final JournalLineRepository journalLines;
    private final GLConfigResolver      glConfig;
    private final ScopeGuard            scopeGuard;

    public StockValuationQuery(JdbcTemplate jdbc,
                                JournalLineRepository journalLines,
                                GLConfigResolver glConfig,
                                ScopeGuard scopeGuard) {
        this.jdbc         = jdbc;
        this.journalLines = journalLines;
        this.glConfig     = glConfig;
        this.scopeGuard   = scopeGuard;
    }

    /**
     * Build the valuation report for the caller's company (assertCanActIn enforced — #1 guard).
     */
    public StockValuationReportDto report(Long companyId) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        // 1. Aggregate on_hand_value + qty per product across all branches (D-6 single-location)
        //    Native SQL join to products for code/name/uid — scalar cross-module read (no entity import).
        List<StockValuationRowDto> rows = new ArrayList<>();

        // FIX F (adversarial review): per-row avgCost is the implied average
        // SUM(on_hand_value) / NULLIF(SUM(quantity), 0) — not MAX(avg_cost), which would
        // return the highest branch avg rather than the true company-level weighted average
        // when stock exists across multiple branches (ADR-0020 D-6 / NFR-INV-06).
        List<Object[]> rawRows = jdbc.query(
                """
                SELECT soh.product_id,
                       p.uid           AS product_uid,
                       p.code          AS product_code,
                       p.name          AS product_name,
                       SUM(soh.quantity)                                        AS total_qty,
                       SUM(soh.on_hand_value)                                   AS total_value,
                       SUM(soh.on_hand_value) / NULLIF(SUM(soh.quantity), 0)   AS avg_cost
                FROM   stock_on_hand soh
                LEFT JOIN products p ON p.id = soh.product_id
                WHERE  soh.company_id = ?
                GROUP BY soh.product_id, p.uid, p.code, p.name
                ORDER BY p.code NULLS LAST
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getLong("product_id"),
                        rs.getString("product_uid"),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getBigDecimal("total_qty"),
                        rs.getBigDecimal("total_value"),
                        rs.getBigDecimal("avg_cost")
                },
                companyId);

        for (Object[] row : rawRows) {
            rows.add(new StockValuationRowDto(
                    (Long) row[0],
                    (String) row[1],
                    (String) row[2],
                    (String) row[3],
                    (BigDecimal) row[4],
                    (BigDecimal) row[6],
                    (BigDecimal) row[5],
                    CURRENCY));
        }

        // 2. Total on_hand_value
        BigDecimal totalValue = rows.stream()
                .map(r -> r.value() != null ? r.value() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 3. Recon bar: compare to GL 1300 account balance (BR-INV-06)
        BigDecimal glBalance = BigDecimal.ZERO;
        try {
            ChartOfAccount inventoryAcct = glConfig.resolve(companyId, GlConfigKey.INVENTORY);
            BigDecimal bal = journalLines.accountBalance(companyId, inventoryAcct.getId());
            glBalance = bal != null ? bal : BigDecimal.ZERO;
        } catch (Exception ex) {
            // GL not configured — recon will show a difference; surfaced on-screen
        }

        StockValuationReconDto recon = StockValuationReconDto.of(
                "Inventory valuation vs GL 1300 Inventory balance",
                totalValue, glBalance);

        return new StockValuationReportDto(companyId, loadCompanyHeader(companyId), rows,
                totalValue, recon, CURRENCY, Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    /**
     * The letterhead the exported PDF prints above the figures — same block as
     * {@link StockReportQuery}. Returns null rather than throwing when the row cannot be read: the
     * report itself never needed the company record, so a header lookup must not be able to take it
     * down.
     */
    private ReportCompanyHeaderDto loadCompanyHeader(Long companyId) {
        List<ReportCompanyHeaderDto> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country
                FROM companies
                WHERE id = ?
                """,
                (rs, rowNum) -> new ReportCompanyHeaderDto(
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
                companyId);
        return found.isEmpty() ? null : found.get(0);
    }
}
