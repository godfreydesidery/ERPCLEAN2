package com.erp.modules.documents.service;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArStatementDto;
import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.render.DocumentRenderModel;
import com.erp.modules.documents.render.DocumentRenderModel.BrandingBlock;
import com.erp.modules.documents.render.DocumentRenderModel.DocLine;
import com.erp.modules.documents.render.DocumentRenderModel.MetaPair;
import com.erp.modules.documents.render.DocumentRenderModel.PartyBlock;
import com.erp.modules.documents.render.DocumentRenderModel.TaxRow;
import com.erp.modules.documents.render.DocumentRenderModel.TotalRow;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.reporting.export.StatementRenderModel;
import com.erp.modules.reporting.export.StatementRenderModel.Row;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.DeliveryLineDto;
import com.erp.modules.sales.domain.dto.QuotationDto;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Per-DocumentType: source DTO → DocumentRenderModel / StatementRenderModel (ADR-0023 D-5).
 * Reads amounts from the source DTO — no recomputation (NFR-DOC-02 / BR-DOC-09).
 */
@Component
public class DocumentModelBuilder {

    private final ObjectMapper objectMapper;

    public DocumentModelBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // -------------------------------------------------------------------------
    // INVOICE (ADR-0023 D-5)
    // -------------------------------------------------------------------------

    public DocumentRenderModel buildInvoice(SalesInvoiceDto inv,
                                             List<SalesInvoiceLineDto> lines,
                                             DocumentBranding branding,
                                             String title) {
        boolean isVoid = "VOID".equalsIgnoreCase(
                inv.status() != null ? inv.status().name() : "");

        BrandingBlock brand = toBrandingBlock(branding);

        List<MetaPair> meta = new ArrayList<>();
        meta.add(new MetaPair("Invoice No.", inv.invoiceNumber()));
        if (inv.finalisedAt() != null) meta.add(new MetaPair("Date", inv.finalisedAt()));
        if (inv.customerName() != null) meta.add(new MetaPair("Customer", inv.customerName()));
        if (inv.currency() != null) meta.add(new MetaPair("Currency", inv.currency()));
        if (inv.agentName() != null) meta.add(new MetaPair("Agent", inv.agentName()));
        if (inv.notes() != null) meta.add(new MetaPair("Notes", inv.notes()));

        PartyBlock party = new PartyBlock(inv.customerName(), List.of(), null);

        List<DocLine> docLines = new ArrayList<>();
        for (SalesInvoiceLineDto l : lines) {
            docLines.add(new DocLine(
                    l.lineNo(), l.productCode(), l.productName(),
                    l.quantity(), l.unitName(),
                    l.unitPriceAmount(), l.lineDiscountAmount(),
                    l.vatStatus() != null ? l.vatStatus().name() : null,
                    l.grossAmount()));
        }

        // Tax summary from JSONB tax_summary (JSON array of band objects)
        List<TaxRow> taxRows = parseTaxSummary(inv.taxSummary());

        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow("Net Total", inv.netTotalAmount(), false));
        totals.add(new TotalRow("VAT Total", inv.vatTotalAmount(), false));
        totals.add(new TotalRow("Gross Total", inv.grossTotalAmount(), true));

        return new DocumentRenderModel(title, brand, meta, party, docLines,
                taxRows, totals, inv.currency(), Instant.now().toString(),
                isVoid ? "VOID" : null);
    }

    // -------------------------------------------------------------------------
    // PURCHASE_ORDER (ADR-0023 D-5)
    // -------------------------------------------------------------------------

    public DocumentRenderModel buildPurchaseOrder(PurchaseOrderDto po,
                                                   List<PurchaseOrderLineDto> lines,
                                                   DocumentBranding branding,
                                                   String title) {
        boolean isVoid = "VOID".equalsIgnoreCase(
                po.status() != null ? po.status().name() : "");

        BrandingBlock brand = toBrandingBlock(branding);

        List<MetaPair> meta = new ArrayList<>();
        meta.add(new MetaPair("PO No.", po.orderNumber()));
        if (po.orderedAt() != null) meta.add(new MetaPair("Date", po.orderedAt().toString()));
        if (po.expectedDate() != null) meta.add(new MetaPair("Expected", po.expectedDate().toString()));
        if (po.supplierName() != null) meta.add(new MetaPair("Supplier", po.supplierName()));
        if (po.currency() != null) meta.add(new MetaPair("Currency", po.currency()));
        if (po.notes() != null) meta.add(new MetaPair("Notes", po.notes()));

        PartyBlock party = new PartyBlock(po.supplierName(), List.of(), null);

        List<DocLine> docLines = new ArrayList<>();
        for (PurchaseOrderLineDto l : lines) {
            docLines.add(new DocLine(
                    l.lineNo(), l.productCode(), l.productName(),
                    l.orderedQty(), l.unitName(),
                    l.unitCostAmount(), null, null,
                    l.lineTotalAmount()));
        }

        List<TotalRow> totals = new ArrayList<>();
        if (po.orderTotalAmount() != null) {
            totals.add(new TotalRow("Order Total", po.orderTotalAmount(), true));
        }

        return new DocumentRenderModel(title, brand, meta, party, docLines,
                List.of(), totals, po.currency(), Instant.now().toString(),
                isVoid ? "VOID" : null);
    }

    // -------------------------------------------------------------------------
    // GOODS_RECEIPT — priced (ADR-0023 D-5 as amended 2026-08-08 / BR-DOC-07 as amended)
    //
    // Kilimanjaro K2: the GRN now prints the per-line cost and the receipt value. The original
    // qty-only rule assumed a supplier copy; in practice the GRN is the internal receiving document
    // the storekeeper and the accounts clerk check the delivery and the supplier bill against, and a
    // GRN with no values cannot be checked against anything.
    //
    // Every figure here is COPIED, never derived (BR-DOC-02 / BR-DOC-09): the unit cost and line
    // total are persisted columns on goods_receipt_lines, and the receipt total is computed by the
    // purchases service and handed over on the DTO.
    //
    // The DELIVERY_NOTE (below) shares this builder/renderer pair and deliberately stays qty-only —
    // it is a shipment document (ADR-0021 D-7) and must not disclose prices to whoever signs for it.
    // -------------------------------------------------------------------------

    public DocumentRenderModel buildGoodsReceipt(GoodsReceiptDto gr,
                                                  List<GoodsReceiptLineDto> lines,
                                                  DocumentBranding branding,
                                                  String title) {
        boolean isVoid = "VOID".equalsIgnoreCase(
                gr.status() != null ? gr.status().name() : "");

        BrandingBlock brand = toBrandingBlock(branding);

        List<MetaPair> meta = new ArrayList<>();
        meta.add(new MetaPair("GRN No.", gr.receiptNumber()));
        if (gr.receivedAt() != null) meta.add(new MetaPair("Received", gr.receivedAt().toString()));
        if (gr.supplierName() != null) meta.add(new MetaPair("Supplier", gr.supplierName()));
        // The renderer never reads model.currency(), so a priced document must state its currency as
        // a meta pair or the printed amounts are unlabelled figures.
        if (gr.currency() != null) meta.add(new MetaPair("Currency", gr.currency()));
        if (gr.notes() != null) meta.add(new MetaPair("Notes", gr.notes()));

        // Supplier name is carried on the DTO from the backing PO snapshot; fall back to blank rather
        // than printing an internal database id on a document a human reads.
        String supplierRef = gr.supplierName() != null ? gr.supplierName() : "";
        PartyBlock party = new PartyBlock(supplierRef, List.of(), null);

        List<DocLine> docLines = new ArrayList<>();
        for (GoodsReceiptLineDto l : lines) {
            docLines.add(new DocLine(
                    l.lineNo(), l.productCode(), l.productName(),
                    l.receivedQty(), l.unitName(),
                    l.unitCostAmount(),
                    // A goods receipt has no discount concept — leaving this null tells the renderer
                    // to drop the Discount column instead of printing an empty one (K2).
                    null,
                    null,
                    l.lineCostAmount()));
        }

        List<TotalRow> totals = new ArrayList<>();
        if (gr.receiptTotalAmount() != null) {
            totals.add(new TotalRow("Total Received Value", gr.receiptTotalAmount(), true));
        }

        return new DocumentRenderModel(title, brand, meta, party, docLines,
                List.of(), totals,
                gr.currency(),
                Instant.now().toString(), isVoid ? "VOID" : null);
    }

    // -------------------------------------------------------------------------
    // DELIVERY_NOTE — qty-only, no prices (ADR-0023 D-5 / ADR-0021 D-7 / BR-DOC-07)
    // -------------------------------------------------------------------------

    public DocumentRenderModel buildDeliveryNote(DeliveryDto del,
                                                  List<DeliveryLineDto> lines,
                                                  DocumentBranding branding,
                                                  String title) {
        BrandingBlock brand = toBrandingBlock(branding);

        List<MetaPair> meta = new ArrayList<>();
        meta.add(new MetaPair("Delivery No.", del.deliveryNumber()));
        if (del.deliveryDate() != null) meta.add(new MetaPair("Date", del.deliveryDate().toString()));
        if (del.salesOrderUid() != null) meta.add(new MetaPair("Sales Order", del.salesOrderUid()));
        if (del.notes() != null) meta.add(new MetaPair("Notes", del.notes()));

        // customerId only — no customer name on DeliveryDto
        String customerRef = del.customerId() != null ? "Customer #" + del.customerId() : "";
        PartyBlock party = new PartyBlock(customerRef, List.of(), null);

        // qty-only: no prices
        List<DocLine> docLines = new ArrayList<>();
        for (DeliveryLineDto l : lines) {
            docLines.add(new DocLine(
                    l.lineNo(), l.productCode(), l.productName(),
                    l.qtyDelivered(), l.unitName(),
                    null, null, null, null));
        }

        return new DocumentRenderModel(title, brand, meta, party, docLines,
                List.of(), List.of(), null, Instant.now().toString(), null);
    }

    // -------------------------------------------------------------------------
    // CREDIT_NOTE (ADR-0023 D-5)
    // -------------------------------------------------------------------------

    public DocumentRenderModel buildCreditNote(ArCreditNoteDto cn,
                                               DocumentBranding branding,
                                               String title) {
        BrandingBlock brand = toBrandingBlock(branding);

        List<MetaPair> meta = new ArrayList<>();
        meta.add(new MetaPair("Credit Note No.", cn.creditNoteNumber()));
        if (cn.noteDate() != null) meta.add(new MetaPair("Date", cn.noteDate().toString()));
        if (cn.reason() != null) meta.add(new MetaPair("Reason", cn.reason()));
        if (cn.currency() != null) meta.add(new MetaPair("Currency", cn.currency()));

        // Customer ref — name not on dto, use id
        String customerRef = cn.customerId() != null ? "Customer #" + cn.customerId() : "";
        PartyBlock party = new PartyBlock(customerRef, List.of(), null);

        // Single credited amount as one line
        List<DocLine> docLines = List.of(new DocLine(
                1, "CN", "Credit note", BigDecimal.ONE, "EA",
                cn.netAmount(), null, null, cn.netAmount()));

        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow("Net Amount", cn.netAmount(), false));
        totals.add(new TotalRow("VAT Amount", cn.vatAmount(), false));
        totals.add(new TotalRow("Total Credit", cn.amount(), true));

        return new DocumentRenderModel(title, brand, meta, party, docLines,
                List.of(), totals, cn.currency(), Instant.now().toString(), null);
    }

    // -------------------------------------------------------------------------
    // QUOTATION — printed as "PROFORMA INVOICE" (K5, Kilimanjaro 2026-08-08)
    // -------------------------------------------------------------------------

    /**
     * Builds the proforma from a priced quotation.
     *
     * <p>The title is NOT hardcoded here — it arrives from the company's {@code document_templates}
     * row, seeded as "PROFORMA INVOICE" by {@code DocumentBrandingSeeder}. Same as every other type,
     * so a tenant can rename it without a code change.
     *
     * <p>Shape matches the tax invoice on purpose: the whole point of a proforma is that the
     * customer can read it as the invoice they are about to receive. Branded header, addressed to
     * the customer by name, one row per line showing unit and unit price, then net / VAT / gross.
     * Amounts are read off the DTO, never recomputed (NFR-DOC-02 / BR-DOC-09); the only arithmetic
     * is summing already-stored per-line VAT into bands for the summary table, which the quotation —
     * unlike the invoice — does not persist as a JSONB snapshot.
     *
     * <p>Rendering is read-only: no stock move, no GL posting, no mutation of the quotation. A
     * proforma is a promise, not a transaction.
     */
    public DocumentRenderModel buildQuotation(QuotationDto q,
                                              List<QuotationLineDto> lines,
                                              DocumentBranding branding,
                                              String title) {
        BrandingBlock brand = toBrandingBlock(branding);

        List<MetaPair> meta = new ArrayList<>();
        // Labelled "Document No." rather than "Proforma No." or "Quotation No.": the title is
        // per-company configurable, so a label that names either one is wrong for the tenant that
        // renamed the template. The QUOTE- prefix on the number already says which series it is.
        // A DRAFT quote has no number yet, and the render service refuses to print one — but the
        // builder still must not print the literal "null" if it is ever called with one.
        if (q.quoteNumber() != null) meta.add(new MetaPair("Document No.", q.quoteNumber()));
        if (q.quoteDate()    != null) meta.add(new MetaPair("Date", q.quoteDate().toString()));
        if (q.validUntil()   != null) meta.add(new MetaPair("Valid Until", q.validUntil().toString()));
        if (q.customerName() != null) meta.add(new MetaPair("Customer", q.customerName()));
        if (q.customerPoNumber() != null) meta.add(new MetaPair("Your Ref.", q.customerPoNumber()));
        if (q.currency()     != null) meta.add(new MetaPair("Currency", q.currency()));
        if (q.notes()        != null) meta.add(new MetaPair("Notes", q.notes()));

        PartyBlock party = new PartyBlock(q.customerName(), List.of(), null);

        List<DocLine> docLines = new ArrayList<>();
        for (QuotationLineDto l : lines) {
            docLines.add(new DocLine(
                    l.lineNo(), l.productCode(), l.productName(),
                    l.quantity(), l.unitName(),
                    l.unitPriceAmount(), l.lineDiscountAmount(),
                    l.vatStatus(),
                    l.grossAmount()));
        }

        List<TotalRow> totals = new ArrayList<>();
        totals.add(new TotalRow("Net Total", q.netTotalAmount(), false));
        totals.add(new TotalRow("VAT Total", q.vatTotalAmount(), false));
        totals.add(new TotalRow("Gross Total", q.grossTotalAmount(), true));

        return new DocumentRenderModel(title, brand, meta, party, docLines,
                quotationTaxRows(lines), totals, q.currency(), Instant.now().toString(),
                quotationStamp(q.status()));
    }

    /**
     * VAT bands for a quotation, aggregated from the lines' own stored net/VAT amounts.
     * Grouped by (VAT status, rate) and printed in first-appearance order so the table reads in the
     * same order as the lines above it.
     */
    private static List<TaxRow> quotationTaxRows(List<QuotationLineDto> lines) {
        Map<String, TaxRow> bands = new LinkedHashMap<>();
        for (QuotationLineDto l : lines) {
            String status = l.vatStatus() != null ? l.vatStatus() : "";
            BigDecimal rate = l.vatRate() != null ? l.vatRate() : BigDecimal.ZERO;
            String key = status + "|" + rate.toPlainString();
            TaxRow seen = bands.get(key);
            BigDecimal net = seen == null ? BigDecimal.ZERO : seen.base();
            BigDecimal vat = seen == null ? BigDecimal.ZERO : seen.vat();
            bands.put(key, new TaxRow(status,
                    net.add(l.netAmount() != null ? l.netAmount() : BigDecimal.ZERO),
                    rate,
                    vat.add(l.vatAmount() != null ? l.vatAmount() : BigDecimal.ZERO)));
        }
        return List.copyOf(bands.values());
    }

    /**
     * The status stamp printed across a proforma. A quotation that is no longer live must say so on
     * its face — reprinting an expired or rejected quote with nothing to distinguish it from a live
     * one is how a stale price gets honoured. SENT/DRAFT print clean; ACCEPTED is stamped too, so a
     * proforma cannot be mistaken for the tax invoice that follows it.
     */
    private static String quotationStamp(String status) {
        if (status == null) return null;
        return switch (status) {
            case "EXPIRED"  -> "EXPIRED";
            case "REJECTED" -> "REJECTED";
            case "ACCEPTED" -> "ACCEPTED — NOT A TAX INVOICE";
            default -> null;
        };
    }

    // -------------------------------------------------------------------------
    // AR_STATEMENT — reuses StatementRenderModel + PdfStatementRenderer (ADR-0023 D-5)
    // -------------------------------------------------------------------------

    public StatementRenderModel buildArStatement(ArStatementDto stmt,
                                                  DocumentBranding branding,
                                                  String title) {
        List<Row> rows = new ArrayList<>();

        rows.add(Row.sectionHeader("Open Invoices"));
        if (stmt.openItems() != null) {
            for (ArInvoiceDto inv : stmt.openItems()) {
                rows.add(Row.line(
                        inv.documentNo() + " due " + inv.dueDate(),
                        inv.outstandingAmount(),
                        null));
            }
        }
        rows.add(Row.total("Total Outstanding", stmt.totalOutstanding(), null));

        String companyName = branding != null ? branding.getDisplayName() : "";
        return new StatementRenderModel(
                title, companyName, stmt.currency(),
                "As at " + stmt.asAt(),
                null,
                Instant.now().toString(),
                rows);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BrandingBlock toBrandingBlock(DocumentBranding b) {
        if (b == null) {
            return new BrandingBlock("", null, null, List.of(), null, null, null, null, null);
        }
        List<String> addr = new ArrayList<>();
        if (b.getAddressLine1() != null) addr.add(b.getAddressLine1());
        if (b.getAddressLine2() != null) addr.add(b.getAddressLine2());
        if (b.getCity() != null || b.getRegion() != null || b.getPostalCode() != null) {
            String cityLine = String.join(", ",
                    nonNull(b.getCity()), nonNull(b.getRegion()), nonNull(b.getPostalCode()))
                    .replaceAll("^, |, $|, ,", "").trim();
            if (!cityLine.isEmpty()) addr.add(cityLine);
        }
        if (b.getCountry() != null) addr.add(b.getCountry());

        String contactLine = null;
        if (b.getContactPhone() != null || b.getContactEmail() != null) {
            contactLine = List.of(nonNull(b.getContactPhone()), nonNull(b.getContactEmail()))
                    .stream().filter(s -> !s.isEmpty()).reduce((a, c) -> a + " | " + c).orElse(null);
        }

        return new BrandingBlock(
                b.getDisplayName(), b.getLegalName(), b.getTaxId(),
                addr, contactLine, b.getLogoRef(), b.getLogoDataUri(),
                b.getFooterTerms(), b.getBankDetails());
    }

    private String nonNull(String s) { return s != null ? s : ""; }

    /**
     * Parse the tax_summary JSONB into printable rows. The array is written by
     * InvoiceTotalsCalculator.buildTaxSummary as {status, rate, net, vat} — the band label is
     * {@code status} and the taxable base is {@code net} (reading "bandLabel"/"base" left the
     * printed invoice's Band + Base columns blank).
     */
    @SuppressWarnings("unchecked")
    private List<TaxRow> parseTaxSummary(String taxSummaryJson) {
        if (taxSummaryJson == null || taxSummaryJson.isBlank()) return List.of();
        try {
            List<Map<String, Object>> bands = objectMapper.readValue(taxSummaryJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<TaxRow> rows = new ArrayList<>();
            for (Map<String, Object> band : bands) {
                rows.add(new TaxRow(
                        str(band.get("status")),
                        decimal(band.get("net")),
                        decimal(band.get("rate")),
                        decimal(band.get("vat"))));
            }
            return rows;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    private String str(Object o) { return o != null ? o.toString() : ""; }
    private BigDecimal decimal(Object o) {
        if (o == null) return null;
        try { return new BigDecimal(o.toString()); } catch (NumberFormatException e) { return null; }
    }
}
