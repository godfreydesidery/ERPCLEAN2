package com.erp.modules.stock.service;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.stock.domain.dto.StockMovementDetailRowDto;
import com.erp.modules.stock.domain.dto.StockMovementReportDto;
import com.erp.modules.stock.domain.dto.StockMovementReportFiltersDto;
import com.erp.modules.stock.domain.dto.StockMovementReportTotalsDto;
import com.erp.modules.stock.domain.dto.StockMovementSummaryRowDto;
import com.erp.modules.stock.domain.enums.StockMovementReportMode;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period Stock Movement report (K9) — OPENING / PURCHASES / SALES / ADJUSTMENTS-OTHER / CLOSING
 * over an arbitrary date range, in SUMMARY (per product) or DETAIL (per movement) form.
 *
 * <p>This is a genuinely period-windowed report, unlike {@link StockReportQuery} /
 * {@link StockValuationQuery} which are present-moment snapshots of {@code stock_on_hand} with no
 * date range at all. Opening at an arbitrary date is DERIVED from the append-only
 * {@code stock_movements} ledger (Σ signed quantity strictly before the period start) rather than
 * read from any stored balance — the ledger is the only thing that can answer "as at 30 June".
 *
 * <h2>Why there is a fifth column</h2>
 * {@code opening + purchases − sales} does NOT equal {@code closing}: transfers, stock counts, bulk
 * imports, opening balances, project issues and production movements all change stock without being
 * a purchase or a sale. The ADJUSTMENTS/OTHER column carries every one of those so the arithmetic
 * visibly closes:
 * <pre>closing = opening + purchases − sales + adjustmentsOther</pre>
 * That identity holds by construction — the three period buckets partition the movement types
 * exhaustively, and closing is simply Σ of every movement up to the period end. Dropping the column
 * would make the report look wrong to the reader even though the numbers were right.
 *
 * <p>SQL shape follows {@link com.erp.modules.sales.service.SalesReportQuery}: native JDBC with
 * scalar cross-module joins (products / units_of_measure / branches), never a cross-module entity or
 * service import (ADR boundary rule).
 *
 * <p>Both shapes are paginated — a busy branch's movement ledger is far too large to ship whole.
 * {@code totals} is computed over the WHOLE matching set, not the current page, so the footer does
 * not shift as the user pages.
 */
@Component
@Transactional(readOnly = true)
public class StockMovementReportQuery {

    private static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    private static final String CURRENCY_FALLBACK = "TZS";

    /** Hard ceiling on a single page — protects the API from an accidental "give me everything". */
    public static final int MAX_PAGE_SIZE = 500;

    /** Default page size when the caller does not ask for one. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * Ceiling on an export. An export of a single 50-row page is useless, so the export path lifts
     * the page cap — but not to infinity: past this the request is refused with a friendly ask to
     * narrow the period, rather than building a 200 MB PDF and timing out the request.
     */
    public static final int MAX_EXPORT_ROWS = 20_000;

    /**
     * Movement types that count as PURCHASES. Signed: a receipt is +, a receipt reversal and a
     * purchase return are −, so the column reads as NET purchased quantity.
     */
    private static final String PURCHASE_TYPES =
            "'GOODS_RECEIPT','GOODS_RECEIPT_REVERSAL','PURCHASE_RETURN'";

    /**
     * Movement types that count as SALES. Signed − in the ledger (an issue reduces stock), so the
     * column is negated to read as a POSITIVE quantity sold; a sale reversal nets it back down.
     */
    private static final String SALES_TYPES = "'SALE_ISSUE','SALE_REVERSAL'";

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public StockMovementReportQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    /**
     * Builds one page of the report.
     *
     * @param companyId tenant scope (asserted against the caller)
     * @param filters   period, mode and optional branch/product narrowing
     * @param page      zero-based page index
     * @param size      page size (clamped to {@link #MAX_PAGE_SIZE})
     */
    public StockMovementReportDto report(Long companyId, StockMovementReportFiltersDto filters,
                                          int page, int size) {
        int safeSize = size <= 0 ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);
        return build(companyId, filters, Math.max(page, 0), safeSize);
    }

    /**
     * Builds the WHOLE report for an export (PDF/XLSX/CSV) rather than a single screen page.
     * Refused with a friendly message past {@link #MAX_EXPORT_ROWS} — the caller is asked to narrow
     * the period or filter by product, which is far better than a request that times out.
     */
    public StockMovementReportDto reportForExport(Long companyId, StockMovementReportFiltersDto filters) {
        StockMovementReportDto dto = build(companyId, filters, 0, MAX_EXPORT_ROWS);
        if (dto.totalElements() > MAX_EXPORT_ROWS) {
            throw new IllegalArgumentException(
                    "This report is too large to export in one file. Please choose a shorter date "
                    + "range, or filter by branch or product, and try again.");
        }
        return dto;
    }

    // -------------------------------------------------------------------------

    private StockMovementReportDto build(Long companyId, StockMovementReportFiltersDto filters,
                                          int safePage, int safeSize) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);

        LocalDate fromDate = filters.fromDate();
        LocalDate toDate   = filters.toDate();
        if (fromDate == null || toDate == null) {
            throw new IllegalArgumentException("Please choose a start date and an end date.");
        }
        if (toDate.isBefore(fromDate)) {
            throw new IllegalArgumentException("The end date cannot be earlier than the start date.");
        }

        StockMovementReportMode effectiveMode =
                filters.mode() != null ? filters.mode() : StockMovementReportMode.SUMMARY;
        String branchUid  = filters.branchUid();
        String productUid = filters.productUid();

        CompanyHeader header = loadCompanyHeader(companyId);
        ZoneId zone = ZoneId.of(header.timeZone() != null ? header.timeZone() : DEFAULT_TIME_ZONE);

        // occurred_at is timestamptz; bind OffsetDateTime, not a raw Instant — the PG JDBC driver
        // cannot infer the SQL type for java.time.Instant via JdbcTemplate.
        OffsetDateTime from = fromDate.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime to   = toDate.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        NamedRef branch  = resolveNamedRef("branches", "name", branchUid,  companyId, "Branch");
        NamedRef product = resolveNamedRef("products", "name", productUid, companyId, "Product");

        StringBuilder filterSql = new StringBuilder();
        List<Object> filterParams = new ArrayList<>();
        if (branch != null) {
            filterSql.append("\n            AND sm.branch_id = ?");
            filterParams.add(branch.id());
        }
        if (product != null) {
            filterSql.append("\n            AND sm.product_id = ?");
            filterParams.add(product.id());
        }

        // Base CTE params, in textual order: the opening cut-off, the tenant, the period end,
        // then any filters.
        List<Object> baseParams = new ArrayList<>();
        baseParams.add(from);
        baseParams.add(companyId);
        baseParams.add(to);
        baseParams.addAll(filterParams);

        String baseCte = baseCte(filterSql.toString());

        // Totals always cover the whole matching set (page-independent footer).
        StockMovementReportTotalsDto totals = queryTotals(baseCte, baseParams);

        List<StockMovementSummaryRowDto> summaryRows = List.of();
        List<StockMovementDetailRowDto>  detailRows  = List.of();
        int totalElements;

        if (effectiveMode == StockMovementReportMode.DETAIL) {
            totalElements = countDetail(baseCte, baseParams);
            detailRows    = queryDetailPage(baseCte, baseParams, safePage, safeSize, zone);
        } else {
            totalElements = countSummary(baseCte, baseParams);
            summaryRows   = querySummaryPage(baseCte, baseParams, safePage, safeSize);
        }

        int totalPages = safeSize > 0
                ? (int) Math.ceil(totalElements / (double) safeSize)
                : 0;

        ReportCompanyHeaderDto companyDto = new ReportCompanyHeaderDto(
                header.name(), header.legalName(),
                header.addressLine1(), header.addressLine2(),
                header.city(), header.region(), header.country(),
                header.contactPhone(), header.contactEmail(),
                header.taxId(), header.vrn());

        return new StockMovementReportDto(
                companyDto,
                fromDate.toString(), toDate.toString(),
                effectiveMode,
                branch != null ? branch.name() : null,
                product != null ? product.name() : null,
                header.baseCurrency() != null ? header.baseCurrency() : CURRENCY_FALLBACK,
                summaryRows, detailRows, totals,
                safePage, safeSize, totalElements, totalPages,
                Instant.now().toString());
    }

    // -------------------------------------------------------------------------
    // SQL
    // -------------------------------------------------------------------------

    /**
     * Every movement up to (but excluding) the period end, tagged with whether it belongs to the
     * OPENING slice (strictly before the period start) or to the period itself. Selecting the whole
     * history once lets opening, the period buckets and closing all fall out of one scan.
     */
    private String baseCte(String filterSql) {
        return """
                WITH m AS (
                    SELECT sm.id                   AS movement_id,
                           sm.uid                  AS movement_uid,
                           sm.product_id           AS product_id,
                           sm.quantity             AS quantity,
                           sm.movement_type        AS movement_type,
                           sm.occurred_at          AS occurred_at,
                           sm.reason_code          AS reason_code,
                           sm.note                 AS note,
                           sm.source_document_type AS source_document_type,
                           sm.source_document_uid  AS source_document_uid,
                           (sm.occurred_at < ?)    AS in_opening
                    FROM stock_movements sm
                    WHERE sm.company_id = ?
                      AND sm.occurred_at < ?"""
                + filterSql
                + "\n)";
    }

    /**
     * Per-product aggregate. The HAVING keeps a product only when it had stock at the period start
     * or moved during the period — otherwise every product ever touched would render as a row of
     * zeroes.
     */
    private String summaryInnerSql() {
        return """
                SELECT m.product_id                              AS product_id,
                       p.code                                    AS product_code,
                       p.name                                    AS product_name,
                       COALESCE(NULLIF(u.symbol, ''), u.code)    AS unit_label,
                       COALESCE(SUM(m.quantity)  FILTER (WHERE m.in_opening), 0)        AS opening_qty,
                       COALESCE(SUM(m.quantity)  FILTER (WHERE NOT m.in_opening
                                 AND m.movement_type IN (""" + PURCHASE_TYPES + """
                                 )), 0)                                                 AS purchases_in,
                       COALESCE(SUM(-m.quantity) FILTER (WHERE NOT m.in_opening
                                 AND m.movement_type IN (""" + SALES_TYPES + """
                                 )), 0)                                                 AS sales_out,
                       COALESCE(SUM(m.quantity)  FILTER (WHERE NOT m.in_opening
                                 AND m.movement_type NOT IN (""" + PURCHASE_TYPES + "," + SALES_TYPES + """
                                 )), 0)                                                 AS adjustments_other,
                       COALESCE(SUM(m.quantity), 0)                                     AS closing_qty
                FROM m
                LEFT JOIN products p ON p.id = m.product_id
                LEFT JOIN units_of_measure u ON u.id = p.base_unit_id
                GROUP BY m.product_id, p.code, p.name, COALESCE(NULLIF(u.symbol, ''), u.code)
                HAVING COALESCE(SUM(m.quantity) FILTER (WHERE m.in_opening), 0) <> 0
                    OR COUNT(*) FILTER (WHERE NOT m.in_opening) > 0
                """;
    }

    private List<StockMovementSummaryRowDto> querySummaryPage(String baseCte, List<Object> baseParams,
                                                               int page, int size) {
        String sql = baseCte + "\n" + summaryInnerSql()
                + "ORDER BY product_code NULLS LAST, product_name NULLS LAST\nLIMIT ? OFFSET ?";
        List<Object> params = new ArrayList<>(baseParams);
        params.add(size);
        params.add((long) page * size);

        return jdbc.query(sql,
                (rs, rowNum) -> new StockMovementSummaryRowDto(
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getString("unit_label"),
                        zeroIfNull(rs.getBigDecimal("opening_qty")),
                        zeroIfNull(rs.getBigDecimal("purchases_in")),
                        zeroIfNull(rs.getBigDecimal("sales_out")),
                        zeroIfNull(rs.getBigDecimal("adjustments_other")),
                        zeroIfNull(rs.getBigDecimal("closing_qty"))),
                params.toArray());
    }

    private int countSummary(String baseCte, List<Object> baseParams) {
        String sql = baseCte + "\nSELECT COUNT(*) FROM (\n" + summaryInnerSql() + ") s";
        Integer count = jdbc.queryForObject(sql, Integer.class, baseParams.toArray());
        return count != null ? count : 0;
    }

    private StockMovementReportTotalsDto queryTotals(String baseCte, List<Object> baseParams) {
        String sql = baseCte + """

                SELECT COALESCE(SUM(s.opening_qty), 0)        AS opening_qty,
                       COALESCE(SUM(s.purchases_in), 0)       AS purchases_in,
                       COALESCE(SUM(s.sales_out), 0)          AS sales_out,
                       COALESCE(SUM(s.adjustments_other), 0)  AS adjustments_other,
                       COALESCE(SUM(s.closing_qty), 0)        AS closing_qty
                FROM (
                """ + summaryInnerSql() + ") s";

        List<StockMovementReportTotalsDto> found = jdbc.query(sql,
                (rs, rowNum) -> new StockMovementReportTotalsDto(
                        zeroIfNull(rs.getBigDecimal("opening_qty")),
                        zeroIfNull(rs.getBigDecimal("purchases_in")),
                        zeroIfNull(rs.getBigDecimal("sales_out")),
                        zeroIfNull(rs.getBigDecimal("adjustments_other")),
                        zeroIfNull(rs.getBigDecimal("closing_qty"))),
                baseParams.toArray());
        return found.isEmpty() ? StockMovementReportTotalsDto.zero() : found.get(0);
    }

    private int countDetail(String baseCte, List<Object> baseParams) {
        String sql = baseCte + "\nSELECT COUNT(*) FROM m WHERE NOT m.in_opening";
        Integer count = jdbc.queryForObject(sql, Integer.class, baseParams.toArray());
        return count != null ? count : 0;
    }

    /**
     * One row per in-period movement with the running balance carried across pages: the window
     * function runs over the WHOLE filtered set inside the CTE, and only the outer SELECT is sliced,
     * so page 7's opening row continues exactly where page 6 stopped.
     */
    private List<StockMovementDetailRowDto> queryDetailPage(String baseCte, List<Object> baseParams,
                                                             int page, int size, ZoneId zone) {
        String sql = baseCte + """
                ,
                opening AS (
                    SELECT m.product_id AS product_id, SUM(m.quantity) AS opening_qty
                    FROM m
                    WHERE m.in_opening
                    GROUP BY m.product_id
                ),
                d AS (
                    SELECT m.*,
                           SUM(m.quantity) OVER (
                               PARTITION BY m.product_id
                               ORDER BY m.occurred_at, m.movement_id
                               ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                           ) AS cum_qty
                    FROM m
                    WHERE NOT m.in_opening
                )
                SELECT d.movement_uid                          AS movement_uid,
                       d.occurred_at                           AS occurred_at,
                       d.movement_type                         AS movement_type,
                       d.quantity                              AS quantity,
                       d.reason_code                           AS reason_code,
                       d.note                                  AS note,
                       d.source_document_type                  AS source_document_type,
                       d.source_document_uid                   AS source_document_uid,
                       p.code                                  AS product_code,
                       p.name                                  AS product_name,
                       COALESCE(NULLIF(u.symbol, ''), u.code)  AS unit_label,
                       COALESCE(o.opening_qty, 0) + d.cum_qty  AS running_balance
                FROM d
                LEFT JOIN products p ON p.id = d.product_id
                LEFT JOIN units_of_measure u ON u.id = p.base_unit_id
                LEFT JOIN opening o ON o.product_id = d.product_id
                ORDER BY product_code NULLS LAST, d.occurred_at, d.movement_id
                LIMIT ? OFFSET ?""";

        List<Object> params = new ArrayList<>(baseParams);
        params.add(size);
        params.add((long) page * size);

        return jdbc.query(sql,
                (rs, rowNum) -> {
                    BigDecimal qty = zeroIfNull(rs.getBigDecimal("quantity"));
                    String reasonCode = rs.getString("reason_code");
                    Timestamp occurredAt = rs.getTimestamp("occurred_at");
                    return new StockMovementDetailRowDto(
                            rs.getString("movement_uid"),
                            // Rendered in the COMPANY's zone (offset preserved), not UTC: the period
                            // window is computed in that zone, so a UTC stamp would put a late-evening
                            // movement on the wrong side of the period boundary on screen.
                            occurredAt != null
                                    ? occurredAt.toInstant().atZone(zone).toOffsetDateTime().toString()
                                    : null,
                            rs.getString("product_code"),
                            rs.getString("product_name"),
                            rs.getString("unit_label"),
                            rs.getString("movement_type"),
                            qty.signum() < 0 ? "OUT" : "IN",
                            qty,
                            zeroIfNull(rs.getBigDecimal("running_balance")),
                            // ADJUSTMENT rows carry a reason code; everything else may carry only a
                            // free-text note — show whichever exists so the column is never blank
                            // when there IS an explanation.
                            reasonCode != null ? reasonCode : rs.getString("note"),
                            rs.getString("source_document_type"),
                            rs.getString("source_document_uid"));
                },
                params.toArray());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private CompanyHeader loadCompanyHeader(Long companyId) {
        List<CompanyHeader> found = jdbc.query(
                """
                SELECT name, legal_name, tax_id, vrn, contact_phone, contact_email,
                       address_line1, address_line2, city, region, country, time_zone, base_currency
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
                        rs.getString("time_zone"),
                        rs.getString("base_currency")),
                companyId);
        if (found.isEmpty()) {
            throw new NotFoundException("Company not found.");
        }
        return found.get(0);
    }

    /** Resolve an optional uid filter to (id, display name), scoped to the caller's company. */
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

    private record NamedRef(Long id, String name) {}

    private record CompanyHeader(String name, String legalName, String taxId, String vrn,
                                  String contactPhone, String contactEmail,
                                  String addressLine1, String addressLine2,
                                  String city, String region, String country,
                                  String timeZone, String baseCurrency) {}
}
