package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintLineDto;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the printed purchase return / debit note (Kilimanjaro issue sheet: "Purchase return —
 * cannot export or print purchase return").
 *
 * <p>The return stores its lines, reason and totals; the note the supplier signs also wants the GRN
 * and PO the goods came in on, the supplier's TIN / VRN and address, the debit note number raised on
 * confirm, and who prepared it. All of that already exists in other tables, so it is read here at
 * print time with scalar native-SQL joins — the {@link GoodsReceiptPrintQuery} pattern — rather than
 * by importing another module's entity or service, and without adding a column to a frozen schema.
 *
 * <p><b>Tenancy.</b> The company is resolved from the return's own uid through
 * {@link ScopeGuard#companyIdOf}, the caller is checked against it, and every read below is then
 * keyed on that company as well as the uid — a uid from another tenant resolves to nothing.
 */
@Component
@Transactional(readOnly = true)
public class PurchaseReturnPrintQuery {

    static final int MONEY_SCALE = 2;
    static final String DEFAULT_TIME_ZONE = "Africa/Dar_es_Salaam";
    static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private static final String NOT_FOUND = "Purchase return not found.";

    private final JdbcTemplate jdbc;
    private final ScopeGuard   scopeGuard;

    public PurchaseReturnPrintQuery(JdbcTemplate jdbc, ScopeGuard scopeGuard) {
        this.jdbc       = jdbc;
        this.scopeGuard = scopeGuard;
    }

    public PurchaseReturnPrintDto byUid(String uid) {
        Long companyId = scopeGuard.companyIdOf("purchasereturn", uid)
                .orElseThrow(() -> new NotFoundException(NOT_FOUND));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Header h = loadHeader(companyId, uid);
        if (h == null) {
            throw new NotFoundException(NOT_FOUND);
        }
        return assemble(h, loadLines(companyId, h.id()));
    }

    // -------------------------------------------------------------------------
    // Assembly — pure, so the formatting and the totals are testable without a database
    // -------------------------------------------------------------------------

    /** The header row as read; package-private so the unit test can build one. */
    record Header(Long id, String uid, Long companyId, String returnNumber, String status,
                  String reason, BigDecimal netAmount, BigDecimal vatAmount, BigDecimal grossAmount,
                  String currency, Instant createdAt, Instant confirmedAt,
                  String supplierSnapshotName, String goodsReceiptNumber, String purchaseOrderNumber,
                  String branchName, String supplierName, String supplierTin, String supplierVrn,
                  String supplierAddress, String supplierDistrict, String supplierRegion,
                  String supplierCountry, String debitNoteNumber, String preparedByName,
                  String timeZone) {}

    static PurchaseReturnPrintDto assemble(Header h, List<PurchaseReturnPrintLineDto> lines) {
        // The business date: when it was confirmed (the goods left), else when it was raised.
        Instant returnedAt = h.confirmedAt() != null ? h.confirmedAt() : h.createdAt();
        String supplier = notBlank(h.supplierName()) ? h.supplierName() : h.supplierSnapshotName();
        return new PurchaseReturnPrintDto(
                h.uid(), h.companyId(), h.returnNumber(), h.status(),
                formatDate(returnedAt, h.timeZone()), returnedAt,
                h.goodsReceiptNumber(), h.purchaseOrderNumber(), h.branchName(),
                supplier, trimToNull(h.supplierTin()), trimToNull(h.supplierVrn()),
                supplierAddress(h),
                h.reason(), h.currency(), h.debitNoteNumber(), trimToNull(h.preparedByName()),
                lines,
                money(h.netAmount()), money(h.vatAmount()), money(h.grossAmount()),
                zoneOf(h.timeZone()).getId());
    }

    /**
     * {@code dd-MMM-yyyy} in the company's zone. A return confirmed at 22:30 UTC on the 31st is the
     * 1st in Dar es Salaam, and the note must carry the date the storekeeper saw. An unknown or
     * blank zone falls back to the platform default rather than failing the print.
     */
    static String formatDate(Instant instant, String timeZone) {
        if (instant == null) {
            return null;
        }
        return DATE_FMT.format(instant.atZone(zoneOf(timeZone)));
    }

    static ZoneId zoneOf(String timeZone) {
        try {
            return ZoneId.of(notBlank(timeZone) ? timeZone : DEFAULT_TIME_ZONE);
        } catch (DateTimeException e) {
            return ZoneId.of(DEFAULT_TIME_ZONE);
        }
    }

    private static BigDecimal money(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static List<String> supplierAddress(Header h) {
        List<String> out = new ArrayList<>();
        addIfPresent(out, h.supplierAddress());
        String districtRegion = joinNonBlank(h.supplierDistrict(), h.supplierRegion());
        addIfPresent(out, districtRegion);
        addIfPresent(out, h.supplierCountry());
        return out;
    }

    private static void addIfPresent(List<String> out, String s) {
        if (notBlank(s)) {
            out.add(s.trim());
        }
    }

    private static String joinNonBlank(String a, String b) {
        if (notBlank(a) && notBlank(b)) return a.trim() + ", " + b.trim();
        if (notBlank(a)) return a.trim();
        return notBlank(b) ? b.trim() : null;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String trimToNull(String s) {
        return notBlank(s) ? s.trim() : null;
    }

    // -------------------------------------------------------------------------
    // SQL
    // -------------------------------------------------------------------------

    private Header loadHeader(Long companyId, String uid) {
        return jdbc.query(
                """
                SELECT pr.id, pr.uid, pr.company_id, pr.return_number, pr.status, pr.reason,
                       pr.net_amount, pr.vat_amount, pr.gross_amount, pr.currency,
                       pr.created_at, pr.confirmed_at,
                       pr.supplier_name     AS supplier_snapshot,
                       gr.receipt_number,
                       po.order_number,
                       b.name               AS branch_name,
                       s.display_name       AS supplier_name,
                       s.tin                AS supplier_tin,
                       s.vrn                AS supplier_vrn,
                       s.physical_address   AS supplier_address,
                       s.district           AS supplier_district,
                       s.region             AS supplier_region,
                       s.country            AS supplier_country,
                       dn.debit_note_number,
                       u.display_name       AS prepared_by,
                       c.time_zone
                FROM purchase_returns pr
                JOIN companies c            ON c.id  = pr.company_id
                LEFT JOIN goods_receipts gr ON gr.id = pr.goods_receipt_id
                                           AND gr.company_id = pr.company_id
                LEFT JOIN purchase_orders po ON po.id = gr.purchase_order_id
                                            AND po.company_id = pr.company_id
                LEFT JOIN branches  b       ON b.id  = pr.branch_id
                LEFT JOIN suppliers s       ON s.id  = pr.supplier_id
                                           AND s.company_id = pr.company_id
                LEFT JOIN ap_debit_notes dn ON dn.uid = pr.debit_note_uid
                                           AND dn.company_id = pr.company_id
                LEFT JOIN app_users u       ON u.id  = pr.created_by
                WHERE pr.company_id = ? AND pr.uid = ?
                """,
                (ResultSetExtractor<Header>) rs -> rs.next()
                        ? new Header(
                                rs.getLong("id"),
                                rs.getString("uid"),
                                rs.getLong("company_id"),
                                rs.getString("return_number"),
                                rs.getString("status"),
                                rs.getString("reason"),
                                rs.getBigDecimal("net_amount"),
                                rs.getBigDecimal("vat_amount"),
                                rs.getBigDecimal("gross_amount"),
                                rs.getString("currency"),
                                instant(rs.getTimestamp("created_at")),
                                instant(rs.getTimestamp("confirmed_at")),
                                rs.getString("supplier_snapshot"),
                                rs.getString("receipt_number"),
                                rs.getString("order_number"),
                                rs.getString("branch_name"),
                                rs.getString("supplier_name"),
                                rs.getString("supplier_tin"),
                                rs.getString("supplier_vrn"),
                                rs.getString("supplier_address"),
                                rs.getString("supplier_district"),
                                rs.getString("supplier_region"),
                                rs.getString("supplier_country"),
                                rs.getString("debit_note_number"),
                                rs.getString("prepared_by"),
                                rs.getString("time_zone"))
                        : null,
                companyId, uid);
    }

    private List<PurchaseReturnPrintLineDto> loadLines(Long companyId, Long returnId) {
        return jdbc.query(
                """
                SELECT line_no, product_code, product_name, unit_name,
                       returned_qty, unit_cost_amount, line_value_amount
                FROM purchase_return_lines
                WHERE company_id = ? AND purchase_return_id = ?
                ORDER BY line_no
                """,
                (rs, rowNum) -> new PurchaseReturnPrintLineDto(
                        rs.getShort("line_no"),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        rs.getString("unit_name"),
                        rs.getBigDecimal("returned_qty"),
                        rs.getBigDecimal("unit_cost_amount"),
                        rs.getBigDecimal("line_value_amount")),
                companyId, returnId);
    }

    private static Instant instant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
