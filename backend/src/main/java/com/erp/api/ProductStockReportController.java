package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.stock.domain.dto.ProductStockReportDto;
import com.erp.modules.stock.domain.dto.ProductStockRowDto;
import com.erp.modules.stock.domain.dto.ProductStockTotalsDto;
import com.erp.modules.stock.service.ProductStockReportQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The two product-stock registers.
 *
 * <ul>
 *   <li>{@code /product-list} — item code, description, on-hand, buying price, selling price and
 *       supplier. No totals: it is a catalogue, and a sum across unrelated products means nothing.</li>
 *   <li>{@code /stock-value} — the same rows plus cost value and sale value per item, filterable to
 *       a single supplier, with grand totals at the foot.</li>
 * </ul>
 *
 * <p>Both are gated on {@code INVENTORY.VALUATION.VIEW} — not {@code PRODUCT.VIEW} — because both
 * disclose buying prices. A cashier who may look up a product must not thereby learn its margin.
 */
@RestController
@RequestMapping("/api/v1/stock/reports")
public class ProductStockReportController {

    private final ProductStockReportQuery query;
    private final TabularExporter         exporter;

    public ProductStockReportController(ProductStockReportQuery query, TabularExporter exporter) {
        this.query    = query;
        this.exporter = exporter;
    }

    // ── Product List ────────────────────────────────────────────────────────

    @GetMapping("/product-list")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public ProductStockReportDto productList(
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid) {
        return query.report(companyId(), branchUid, supplierUid, false);
    }

    @GetMapping("/product-list/export")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportProductList(
            @RequestParam(defaultValue = "PDF") ExportFormat format,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid) {
        ProductStockReportDto dto = query.report(companyId(), branchUid, supplierUid, false);
        return download(exporter.export(flattenProductList(dto), format));
    }

    // ── Stock Value ─────────────────────────────────────────────────────────

    @GetMapping("/stock-value")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public ProductStockReportDto stockValue(
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid) {
        return query.report(companyId(), branchUid, supplierUid, true);
    }

    @GetMapping("/stock-value/export")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStockValue(
            @RequestParam(defaultValue = "PDF") ExportFormat format,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid) {
        ProductStockReportDto dto = query.report(companyId(), branchUid, supplierUid, true);
        return download(exporter.export(flattenStockValue(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flattenProductList(ProductStockReportDto dto) {
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Supplier", Align.LEFT),
                new Column("Qty On Hand", Align.RIGHT),
                new Column("Buying Price", Align.RIGHT),
                new Column(sellingHeader(dto), Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (ProductStockRowDto r : dto.rows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    r.supplierName() != null ? r.supplierName() : "—",
                    fmtQty(r.quantityOnHand()),
                    fmtAmt(r.buyingPrice()),
                    fmtAmt(r.sellingPrice())));
        }
        // No totals row: this is a catalogue listing, by design.
        return new TabularRenderModel("Product List", headerLines(dto), dto.generatedAt(),
                columns, rows, null);
    }

    private TabularRenderModel flattenStockValue(ProductStockReportDto dto) {
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Supplier", Align.LEFT),
                new Column("Qty On Hand", Align.RIGHT),
                new Column("Buying Price", Align.RIGHT),
                new Column("Cost Value", Align.RIGHT),
                new Column(sellingHeader(dto), Align.RIGHT),
                new Column("Sale Value", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (ProductStockRowDto r : dto.rows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    r.supplierName() != null ? r.supplierName() : "—",
                    fmtQty(r.quantityOnHand()),
                    fmtAmt(r.buyingPrice()),
                    fmtAmt(r.costValue()),
                    fmtAmt(r.sellingPrice()),
                    fmtAmt(r.saleValue())));
        }

        ProductStockTotalsDto t = dto.totals();
        List<String> totalsRow = t == null ? null : List.of(
                "", "TOTAL", "", fmtQty(t.totalQuantity()), "",
                fmtAmt(t.totalCostValue()), "", fmtAmt(t.totalSaleValue()));

        return new TabularRenderModel("Stock Value", headerLines(dto), dto.generatedAt(),
                columns, rows, totalsRow);
    }

    /** Names the VAT stance in the column head — "selling price" alone is ambiguous to a shopkeeper. */
    private String sellingHeader(ProductStockReportDto dto) {
        if (dto.priceListName() == null) {
            return "Selling Price";
        }
        return dto.priceIncludesVat() ? "Selling Price (VAT incl.)" : "Selling Price (excl. VAT)";
    }

    private List<String> headerLines(ProductStockReportDto dto) {
        List<String> lines = new ArrayList<>();
        ReportCompanyHeaderDto c = dto.company();
        if (c != null) {
            addIfPresent(lines, c.name());
            String address = joinAddress(c);
            if (address != null) {
                lines.add(address);
            }
            if (c.contactPhone() != null) {
                lines.add("Tel: " + c.contactPhone());
            }
            if (c.contactEmail() != null) {
                lines.add("Email: " + c.contactEmail());
            }
            if (c.taxId() != null) {
                lines.add("TIN: " + c.taxId());
            }
            if (c.vrn() != null) {
                lines.add("VRN: " + c.vrn());
            }
        }
        // An unfiltered run is company-wide, and says so. "All branches" on its own is read by some
        // as "the branches I work in" — which matters, because filtering to a branch a caller is not
        // assigned to is refused while the unfiltered figures still cover that branch.
        lines.add("Branch: " + (dto.branchName() != null
                ? dto.branchName()
                : "All branches (whole company)"));
        lines.add("Supplier: " + (dto.supplierName() != null ? dto.supplierName() : "All suppliers"));
        lines.add("Price list: "
                + (dto.priceListName() != null ? dto.priceListName() : "none set — selling prices blank"));

        // The till is deliberately price-list-blind (ADR-0048) and refuses to derive a unit price
        // from a pack row, both of which this report does. With one price list the two agree; with
        // two they can differ per product. Say so, or the shopkeeper argues with the number.
        if (dto.priceListName() != null) {
            lines.add("Selling prices are the " + dto.priceListName() + " list price; the till may "
                    + "apply a customer price, promotion or pack price.");
        }

        // State what the totals leave out, on the page itself. A reader who cannot see this would
        // take an understated total at face value.
        ProductStockTotalsDto t = dto.totals();
        if (t != null && (t.unvaluedItems() > 0 || t.unpricedItems() > 0)) {
            lines.add("Excluded from totals: " + t.unvaluedItems() + " item(s) with no cost, "
                    + t.unpricedItems() + " with no selling price");
        }
        // ...and what it pulls IN that the catalogue listing does not, for the same reason.
        if (t != null && t.discontinuedItems() > 0) {
            lines.add("Includes " + t.discontinuedItems()
                    + " discontinued item(s) that still hold stock");
        }
        return lines;
    }

    private void addIfPresent(List<String> lines, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value);
        }
    }

    private String joinAddress(ReportCompanyHeaderDto c) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, c.addressLine1());
        appendPart(sb, c.addressLine2());
        appendPart(sb, c.city());
        appendPart(sb, c.region());
        appendPart(sb, c.country());
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part);
    }

    private Long companyId() {
        return RequestContext.get().companyId();
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /** Blank, never "0.00" — an unpriced item is unknown, not free. */
    private String fmtAmt(BigDecimal amt) {
        return amt != null ? String.format("%,.2f", amt) : "";
    }

    /**
     * Whole quantities print without decimals; fractional ones keep 3 dp, the same shape the stock
     * movement export uses.
     *
     * <p>{@code stock_on_hand.quantity} is {@code NUMERIC(19,6)} and weighed goods and pack/base-unit
     * conversion make fractions ordinary data, not an edge case. Rounding to whole units printed
     * 12.5 kg as "13" beside an unrounded cost value — arithmetic the reader cannot reproduce — and
     * printed 0.4 kg as "0", which reads as out of stock. The grand total goes through here too, so
     * a foot made of fractional lines adds up on the page as well.
     */
    private String fmtQty(BigDecimal qty) {
        if (qty == null) {
            return "";
        }
        return qty.stripTrailingZeros().scale() <= 0
                ? String.format("%,.0f", qty)
                : String.format("%,.3f", qty);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
