package com.erp.modules.stock.service;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StockValuationQuery.class);

    private static final String CURRENCY = "TZS";

    private static final String RECON_LABEL = "Inventory valuation vs GL 1300 Inventory balance";

    private final JdbcTemplate          jdbc;
    private final JournalLineRepository journalLines;
    private final ScopeGuard            scopeGuard;

    public StockValuationQuery(JdbcTemplate jdbc,
                                JournalLineRepository journalLines,
                                ScopeGuard scopeGuard) {
        this.jdbc         = jdbc;
        this.journalLines = journalLines;
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

        // 3. Recon bar: compare to the GL Inventory account balance (BR-INV-06)
        StockValuationReconDto recon = reconcileToGl(companyId, totalValue);

        return new StockValuationReportDto(companyId, loadCompanyHeader(companyId), rows,
                totalValue, recon, CURRENCY, Instant.now().toString());
    }

    // -------------------------------------------------------------------------

    /**
     * Reconciles Σ {@code on_hand_value} to the GL Inventory account balance (BR-INV-06).
     *
     * <p>"The Inventory account is not set up" is its own outcome, never a zero balance. It used to
     * be swallowed by a blanket {@code catch (Exception)} that left {@code glBalance = 0}, so a shop
     * that had simply never mapped the account was told its GL was out by the entire value of its
     * stock — an alarm about a discrepancy that did not exist, on a report whose whole job is to
     * raise that alarm truthfully.
     *
     * <p>The mapping is read here with a scalar SQL lookup rather than through
     * {@code GLConfigResolver}, for two reasons. The resolver signals "not configured" by throwing
     * from inside its own {@code MANDATORY} transaction boundary, which marks this read-only
     * transaction rollback-only — so the caught-and-continued path could not deliver its report
     * anyway. And a missing mapping is a normal, expected state for this screen, not an error: it is
     * a question to ask, not an exception to catch. Same scalar cross-module read pattern the
     * product/company joins above already use (no entity import crosses the module line).
     *
     * <p>A genuine failure to READ the balance is deliberately not caught: swallowing it would put
     * an invented number on a finance report, which is the very bug this method exists to fix.
     */
    private StockValuationReconDto reconcileToGl(Long companyId, BigDecimal totalValue) {
        List<Object[]> mapping = jdbc.query(
                """
                SELECT coa.id        AS account_id,
                       coa.is_active AS is_active
                FROM   gl_configs gc
                LEFT JOIN chart_of_accounts coa ON coa.id = gc.account_id
                WHERE  gc.company_id = ?
                  AND  gc.config_key = ?
                """,
                (rs, rowNum) -> new Object[]{
                        rs.getObject("account_id"),
                        rs.getBoolean("is_active")
                },
                companyId, GlConfigKey.INVENTORY.name());

        if (mapping.isEmpty()) {
            log.debug("Stock valuation recon: no {} account mapped for company={} — "
                    + "reporting NOT CONFIGURED rather than a zero GL balance",
                    GlConfigKey.INVENTORY, companyId);
            return StockValuationReconDto.glAccountNotMapped(RECON_LABEL, totalValue);
        }

        Object[] row       = mapping.get(0);
        Object   accountId = row[0];
        boolean  active    = (Boolean) row[1];
        if (accountId == null || !active) {
            log.warn("Stock valuation recon: {} account mapped for company={} is missing or inactive "
                    + "— reporting NOT CONFIGURED rather than a zero GL balance",
                    GlConfigKey.INVENTORY, companyId);
            return StockValuationReconDto.glAccountNotUsable(RECON_LABEL, totalValue);
        }

        // A null balance means the account carries no journal lines at all — that is a genuine zero
        // balance, not an unknown one, and a stock ledger with value against it IS a real break.
        BigDecimal balance = journalLines.accountBalance(companyId, ((Number) accountId).longValue());
        return StockValuationReconDto.of(RECON_LABEL, totalValue,
                balance != null ? balance : BigDecimal.ZERO);
    }

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
