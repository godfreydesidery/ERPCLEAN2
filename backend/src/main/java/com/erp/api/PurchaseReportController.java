package com.erp.api;

import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterDto;
import com.erp.modules.purchases.domain.dto.GoodsReceivedRegisterRowDto;
import com.erp.modules.purchases.domain.dto.OpenPurchaseOrderRowDto;
import com.erp.modules.purchases.domain.dto.OpenPurchaseOrdersDto;
import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceDto;
import com.erp.modules.purchases.domain.dto.PurchasePriceVarianceRowDto;
import com.erp.modules.purchases.domain.dto.PurchasesBySupplierDto;
import com.erp.modules.purchases.domain.dto.PurchasesBySupplierRowDto;
import com.erp.modules.purchases.service.GoodsReceivedRegisterQuery;
import com.erp.modules.purchases.service.OpenPurchaseOrdersQuery;
import com.erp.modules.purchases.service.PurchasePriceVarianceQuery;
import com.erp.modules.purchases.service.PurchasesBySupplierQuery;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.platform.security.PermissionChecks;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Purchase reports: Goods Received Register, Purchases by Supplier, Open Purchase Orders and
 * Purchase Price Variance.
 *
 * <h2>Gates</h2>
 * Each report is gated on the code of the existing screen that already shows the same figures, so a
 * report never discloses more than that screen does at that gate:
 * <ul>
 *   <li>Goods Received Register, Purchases by Supplier — {@code PURCHASE.GOODS_RECEIPT.VIEW}. The
 *       goods-receipt screens show each line's unit cost and amount at that code, so the register's
 *       cost columns are no wider a disclosure.</li>
 *   <li>Open Purchase Orders — {@code PURCHASE.ORDER.VIEW}, the purchase-order screens' code.</li>
 *   <li>Purchase Price Variance — {@code PURCHASE.ORDER.VIEW} AND {@code PURCHASE.GOODS_RECEIPT.VIEW}:
 *       it sets order prices beside receipt costs.</li>
 * </ul>
 * Columns drawn from a module behind a further code are shown only to a caller who holds it, and are
 * otherwise null (the screen hides them): supplier-bill figures need {@code AP.VIEW} (the Payables
 * screens' code); purchase-return figures need {@code PURCHASE.RETURN.VIEW}. Every export also needs
 * {@code REPORT.EXPORT} on top of the on-screen code. There is no dedicated purchase-report
 * permission — adding one would be a seed change.
 */
@RestController
@RequestMapping("/api/v1/reports/purchases")
public class PurchaseReportController {

    private final GoodsReceivedRegisterQuery  registerQuery;
    private final PurchasesBySupplierQuery    bySupplierQuery;
    private final OpenPurchaseOrdersQuery     openOrdersQuery;
    private final PurchasePriceVarianceQuery  varianceQuery;
    private final TabularExporter             exporter;
    private final PermissionChecks            perm;

    public PurchaseReportController(GoodsReceivedRegisterQuery registerQuery,
                                    PurchasesBySupplierQuery bySupplierQuery,
                                    OpenPurchaseOrdersQuery openOrdersQuery,
                                    PurchasePriceVarianceQuery varianceQuery,
                                    TabularExporter exporter,
                                    PermissionChecks perm) {
        this.registerQuery   = registerQuery;
        this.bySupplierQuery = bySupplierQuery;
        this.openOrdersQuery = openOrdersQuery;
        this.varianceQuery   = varianceQuery;
        this.exporter        = exporter;
        this.perm            = perm;
    }

    // =========================================================================
    // Goods Received Register
    // =========================================================================

    @GetMapping("/goods-received")
    @PreAuthorize("@perm.has('PURCHASE.GOODS_RECEIPT.VIEW')")
    public GoodsReceivedRegisterDto goodsReceived(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false) String productUid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return registerQuery.report(companyId(), fromDate, toDate, branchUid, supplierUid,
                productUid, page, size);
    }

    @GetMapping("/goods-received/export")
    @PreAuthorize("@perm.has('PURCHASE.GOODS_RECEIPT.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportGoodsReceived(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false) String productUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        GoodsReceivedRegisterDto dto = registerQuery.reportForExport(companyId(), fromDate, toDate,
                branchUid, supplierUid, productUid);
        return download(exporter.export(flattenRegister(dto), format));
    }

    // =========================================================================
    // Purchases by Supplier
    // =========================================================================

    @GetMapping("/by-supplier")
    @PreAuthorize("@perm.has('PURCHASE.GOODS_RECEIPT.VIEW')")
    public PurchasesBySupplierDto bySupplier(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid) {
        return bySupplierQuery.report(companyId(), fromDate, toDate, branchUid,
                perm.has("PURCHASE.RETURN.VIEW"), perm.has("AP.VIEW"));
    }

    @GetMapping("/by-supplier/export")
    @PreAuthorize("@perm.has('PURCHASE.GOODS_RECEIPT.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportBySupplier(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        PurchasesBySupplierDto dto = bySupplierQuery.report(companyId(), fromDate, toDate, branchUid,
                perm.has("PURCHASE.RETURN.VIEW"), perm.has("AP.VIEW"));
        return download(exporter.export(flattenBySupplier(dto), format));
    }

    // =========================================================================
    // Open Purchase Orders
    // =========================================================================

    @GetMapping("/open-orders")
    @PreAuthorize("@perm.has('PURCHASE.ORDER.VIEW')")
    public OpenPurchaseOrdersDto openOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid) {
        return openOrdersQuery.report(companyId(), asOfDate, branchUid, supplierUid);
    }

    @GetMapping("/open-orders/export")
    @PreAuthorize("@perm.has('PURCHASE.ORDER.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportOpenOrders(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOfDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        OpenPurchaseOrdersDto dto = openOrdersQuery.report(companyId(), asOfDate, branchUid, supplierUid);
        return download(exporter.export(flattenOpenOrders(dto), format));
    }

    // =========================================================================
    // Purchase Price Variance
    // =========================================================================

    @GetMapping("/price-variance")
    @PreAuthorize("@perm.has('PURCHASE.ORDER.VIEW') and @perm.has('PURCHASE.GOODS_RECEIPT.VIEW')")
    public PurchasePriceVarianceDto priceVariance(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid) {
        return varianceQuery.report(companyId(), fromDate, toDate, branchUid, supplierUid,
                perm.has("AP.VIEW"));
    }

    @GetMapping("/price-variance/export")
    @PreAuthorize("@perm.has('PURCHASE.ORDER.VIEW') and @perm.has('PURCHASE.GOODS_RECEIPT.VIEW')"
            + " and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportPriceVariance(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        PurchasePriceVarianceDto dto = varianceQuery.report(companyId(), fromDate, toDate, branchUid,
                supplierUid, perm.has("AP.VIEW"));
        return download(exporter.export(flattenVariance(dto), format));
    }

    // =========================================================================
    // Flatteners
    // =========================================================================

    private TabularRenderModel flattenRegister(GoodsReceivedRegisterDto dto) {
        List<String> head = companyLines(dto.company());
        head.add("From " + dto.fromDate() + " To " + dto.toDate());
        head.add(dto.branchName() != null ? "Branch: " + dto.branchName() : "All branches");
        if (dto.supplierName() != null) head.add("Supplier: " + dto.supplierName());
        if (dto.productName() != null) head.add("Product: " + dto.productName());
        head.add("Values exclude VAT. A voided receipt is shown again, negative, on the day it was voided.");
        if (dto.totals().rowsInOtherCurrency() > 0) {
            head.add(dto.totals().rowsInOtherCurrency() + " line(s) in another currency are not in the "
                    + dto.currency() + " total.");
        }

        List<Column> columns = List.of(
                new Column("Date", Align.LEFT),
                new Column("GRN", Align.LEFT),
                new Column("PO", Align.LEFT),
                new Column("Supplier", Align.LEFT),
                new Column("Branch", Align.LEFT),
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Qty", Align.RIGHT),
                new Column("Unit Cost", Align.RIGHT),
                new Column("Value", Align.RIGHT));
        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (GoodsReceivedRegisterRowDto r : dto.rows()) {
            rows.add(List.of(
                    day(r.entryAt()),
                    nz(r.receiptNumber()) + ("VOID".equals(r.entryType()) ? " (void)" : ""),
                    r.direct() ? "Direct" : nz(r.orderNumber()),
                    nz(r.supplierName()),
                    nz(r.branchName()),
                    nz(r.productCode()),
                    nz(r.productName()) + (r.unitName() != null ? " (" + r.unitName() + ")" : ""),
                    qty(r.quantity()),
                    amt(r.unitCost()),
                    amt(r.value()) + ccySuffix(r.currency(), dto.currency())));
        }
        List<String> totals = List.of("", "TOTAL", "", dto.totals().receipts() + " receipt(s)",
                dto.totals().voids() > 0 ? dto.totals().voids() + " void(s)" : "", "", "", "", "",
                amt(dto.totals().value()));
        return new TabularRenderModel("Goods Received Register", head, dto.generatedAt(),
                columns, rows, totals);
    }

    private TabularRenderModel flattenBySupplier(PurchasesBySupplierDto dto) {
        List<String> head = companyLines(dto.company());
        head.add("From " + dto.fromDate() + " To " + dto.toDate());
        head.add(dto.branchName() != null ? "Branch: " + dto.branchName() : "All branches");
        head.add("Values exclude VAT; Unpaid is what is still owed today on the bills dated in the period.");
        if (dto.totals().rowsInOtherCurrency() > 0) {
            head.add(dto.totals().rowsInOtherCurrency() + " supplier row(s) in another currency are not in the "
                    + dto.currency() + " totals.");
        }

        List<Column> columns = new ArrayList<>(List.of(
                new Column("Code", Align.LEFT),
                new Column("Supplier", Align.LEFT),
                new Column("Receipts", Align.RIGHT),
                new Column("Received", Align.RIGHT)));
        if (dto.returnsShown()) {
            columns.add(new Column("Returns", Align.RIGHT));
            columns.add(new Column("Net Purchases", Align.RIGHT));
        }
        if (dto.billsShown()) {
            columns.add(new Column("Billed", Align.RIGHT));
            columns.add(new Column("Unpaid", Align.RIGHT));
        }
        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (PurchasesBySupplierRowDto r : dto.rows()) {
            String sfx = ccySuffix(r.currency(), dto.currency());
            List<String> row = new ArrayList<>(List.of(
                    nz(r.supplierCode()), nz(r.supplierName()),
                    String.valueOf(r.receipts()), amt(r.receivedValue()) + sfx));
            if (dto.returnsShown()) {
                row.add(amt(r.returnsValue()) + sfx);
                row.add(amt(r.netPurchases()) + sfx);
            }
            if (dto.billsShown()) {
                row.add(amt(r.billedAmount()) + sfx);
                row.add(amt(r.unpaidAmount()) + sfx);
            }
            rows.add(row);
        }
        var t = dto.totals();
        List<String> totals = new ArrayList<>(List.of("", "TOTAL", String.valueOf(t.receipts()),
                amt(t.receivedValue())));
        if (dto.returnsShown()) {
            totals.add(amt(t.returnsValue()));
            totals.add(amt(t.netPurchases()));
        }
        if (dto.billsShown()) {
            totals.add(amt(t.billedAmount()));
            totals.add(amt(t.unpaidAmount()));
        }
        return new TabularRenderModel("Purchases by Supplier", head, dto.generatedAt(),
                columns, rows, totals);
    }

    private TabularRenderModel flattenOpenOrders(OpenPurchaseOrdersDto dto) {
        List<String> head = companyLines(dto.company());
        head.add("As at " + dto.asOfDate());
        head.add(dto.branchName() != null ? "Branch: " + dto.branchName() : "All branches");
        if (dto.supplierName() != null) head.add("Supplier: " + dto.supplierName());
        head.add("Quantities are in the unit ordered. Values exclude VAT.");
        if (dto.totals().rowsInOtherCurrency() > 0) {
            head.add(dto.totals().rowsInOtherCurrency() + " line(s) in another currency are not in the "
                    + dto.currency() + " total.");
        }

        List<Column> columns = List.of(
                new Column("PO", Align.LEFT),
                new Column("Date", Align.LEFT),
                new Column("Expected", Align.LEFT),
                new Column("Supplier", Align.LEFT),
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Ordered", Align.RIGHT),
                new Column("Received", Align.RIGHT),
                new Column("Outstanding", Align.RIGHT),
                new Column("Value", Align.RIGHT),
                new Column("Age (days)", Align.RIGHT));
        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (OpenPurchaseOrderRowDto r : dto.rows()) {
            rows.add(List.of(
                    nz(r.orderNumber()),
                    nz(r.orderDate()),
                    r.expectedDate() != null
                            ? r.expectedDate() + (r.overdue() ? " (late)" : "") : "",
                    nz(r.supplierName()),
                    nz(r.productCode()),
                    nz(r.productName()) + (r.unitName() != null ? " (" + r.unitName() + ")" : ""),
                    qty(r.orderedQty()),
                    qty(r.receivedQty()),
                    qty(r.outstandingQty()),
                    amt(r.outstandingValue()) + ccySuffix(r.currency(), dto.currency()),
                    String.valueOf(r.ageDays())));
        }
        List<String> totals = List.of("TOTAL", dto.totals().orders() + " order(s)", "", "", "",
                dto.totals().lines() + " line(s)", "", "", "", amt(dto.totals().outstandingValue()), "");
        return new TabularRenderModel("Open Purchase Orders", head, dto.generatedAt(),
                columns, rows, totals);
    }

    private TabularRenderModel flattenVariance(PurchasePriceVarianceDto dto) {
        List<String> head = companyLines(dto.company());
        head.add("From " + dto.fromDate() + " To " + dto.toDate());
        head.add(dto.branchName() != null ? "Branch: " + dto.branchName() : "All branches");
        if (dto.supplierName() != null) head.add("Supplier: " + dto.supplierName());
        head.add("Variance = actual price - PO price; positive means more was paid than ordered.");
        if (!dto.billsShown()) {
            head.add("Supplier-bill prices are not shown: they need Accounts Payable view access.");
        }
        if (dto.totals().rowsInOtherCurrency() > 0) {
            head.add(dto.totals().rowsInOtherCurrency() + " line(s) in another currency are not in the "
                    + dto.currency() + " totals.");
        }

        List<Column> columns = new ArrayList<>(List.of(
                new Column("Date", Align.LEFT),
                new Column("GRN", Align.LEFT),
                new Column("Supplier", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Qty", Align.RIGHT),
                new Column("PO Price", Align.RIGHT),
                new Column("GRN Cost", Align.RIGHT),
                new Column("GRN Var", Align.RIGHT)));
        if (dto.billsShown()) {
            columns.add(new Column("Bill Price", Align.RIGHT));
            columns.add(new Column("Bill Var/Unit", Align.RIGHT));
            columns.add(new Column("Bill Var", Align.RIGHT));
            columns.add(new Column("Bill Var %", Align.RIGHT));
        }
        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (PurchasePriceVarianceRowDto r : dto.rows()) {
            List<String> row = new ArrayList<>(List.of(
                    day(r.receivedAt()),
                    nz(r.receiptNumber()),
                    nz(r.supplierName()),
                    nz(r.productCode()) + " " + nz(r.productName()),
                    qty(r.receivedQty()),
                    amt(r.poPrice()),
                    amt(r.receiptCost()),
                    amt(r.receiptVarianceTotal()) + ccySuffix(r.currency(), dto.currency())));
            if (dto.billsShown()) {
                row.add(amt(r.billPrice()));
                row.add(amt(r.billVariancePerUnit()));
                row.add(amt(r.billVarianceTotal()));
                row.add(r.billVariancePct() != null ? r.billVariancePct().toPlainString() + "%" : "");
            }
            rows.add(row);
        }
        var t = dto.totals();
        List<String> totals = new ArrayList<>(List.of("", "TOTAL", t.lines() + " line(s)", "", "", "", "",
                amt(t.receiptVarianceTotal())));
        if (dto.billsShown()) {
            totals.add("");
            totals.add("");
            totals.add(amt(t.billVarianceTotal()));
            totals.add("");
        }
        return new TabularRenderModel("Purchase Price Variance", head, dto.generatedAt(),
                columns, rows, totals);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static Long companyId() {
        return RequestContext.get().companyId();
    }

    private static List<String> companyLines(ReportCompanyHeaderDto c) {
        List<String> lines = new ArrayList<>();
        if (c == null) {
            return lines;
        }
        if (c.name() != null) lines.add(c.name());
        StringBuilder address = new StringBuilder();
        for (String part : new String[] {c.addressLine1(), c.addressLine2(), c.city(), c.region(), c.country()}) {
            if (part != null && !part.isBlank()) {
                if (address.length() > 0) address.append(", ");
                address.append(part);
            }
        }
        if (address.length() > 0) lines.add(address.toString());
        if (c.contactPhone() != null) lines.add("Tel: " + c.contactPhone());
        if (c.contactEmail() != null) lines.add("Email: " + c.contactEmail());
        if (c.taxId() != null) lines.add("TIN: " + c.taxId());
        if (c.vrn() != null) lines.add("VRN: " + c.vrn());
        return lines;
    }

    /** The calendar day of an ISO-8601 timestamp already rendered in the company's offset. */
    private static String day(String iso) {
        return iso != null && iso.length() >= 10 ? iso.substring(0, 10) : "";
    }

    private static String ccySuffix(String rowCurrency, String baseCurrency) {
        return rowCurrency != null && !rowCurrency.equals(baseCurrency) ? " " + rowCurrency : "";
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String amt(BigDecimal v) {
        return v != null ? String.format("%,.2f", v) : "";
    }

    /** Up to 3 dp, trailing zeros dropped — a weighed 2.5 kg must not print as 3. */
    private static String qty(BigDecimal v) {
        if (v == null) {
            return "";
        }
        BigDecimal scaled = v.setScale(3, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        if (scaled.scale() < 0) {
            scaled = scaled.setScale(0);
        }
        return String.format("%,." + scaled.scale() + "f", scaled);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
