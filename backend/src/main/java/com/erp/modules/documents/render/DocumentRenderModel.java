package com.erp.modules.documents.render;

import java.math.BigDecimal;
import java.util.List;

/**
 * Richer render model for transactional documents (ADR-0023 D-5).
 * Captures: branded header + line table + tax summary + totals block.
 * Distinct from StatementRenderModel which is statement-shaped.
 * DocumentPdfRenderer renders this via OpenPDF primitives (one shared code path, NFR-DOC-01).
 */
public record DocumentRenderModel(
        String          title,
        BrandingBlock   branding,
        List<MetaPair>  meta,
        PartyBlock      counterparty,
        List<DocLine>   lines,
        List<TaxRow>    taxSummary,
        List<TotalRow>  totals,
        String          currency,
        String          generatedAt,
        /** null, or "VOID" for a voided source (D-6 / BR-DOC-05). */
        String          voidLabel,
        /** Per-document-type layout switches; never null — use {@link Layout#plain()}. */
        Layout          layout
) {

    /**
     * Layout switches that differ per document type (K9).
     *
     * <p>Deliberately explicit rather than inferred from the data. Every builder already populates
     * {@code DocLine.code}, so a renderer that showed the item-code column "whenever a code is
     * present" would silently redesign the invoice, the proforma and the delivery note the day the
     * GRN asked for one. Opting in per type keeps a layout change confined to the document that
     * asked for it.
     *
     * @param showItemCode  print the item-code column ahead of the description
     * @param signatories   sign-off labels printed as blank ruled lines at the foot ("Prepared By",
     *                      "Checked By", …); empty for documents that are not signed on paper
     * @param printFooter   the print footprint line (printed on / at / by / from); null to omit
     */
    public record Layout(
            boolean      showItemCode,
            List<String> signatories,
            String       printFooter
    ) {
        public Layout {
            signatories = signatories != null ? List.copyOf(signatories) : List.of();
        }

        /** The default: no item-code column, no signature block, no print footprint. */
        public static Layout plain() {
            return new Layout(false, List.of(), null);
        }
    }

    /** Branding header block derived from DocumentBranding or companies fallback. */
    public record BrandingBlock(
            String         displayName,
            String         legalName,
            String         taxId,
            /**
             * VAT registration number, always read from {@code companies.vrn} at render time.
             *
             * <p>Unlike {@code legalName} and {@code taxId} there is no snapshot to prefer:
             * {@code document_branding} has no {@code vrn} column (V19 created the table with
             * {@code tax_id} only, and no later migration adds one). Adding one was the obvious
             * route and was rejected as the more expensive of two options — it would need a
             * migration against a durable database, a DTO field, a form field and a template
             * change, to buy a per-company override nobody has asked for.
             *
             * <p>The cost of reading it live is that a company cannot suppress or override its VRN
             * per document. That is the correct default anyway: a VAT-registered supplier's number
             * belongs on its tax invoices, and "printed something different from the company
             * record" is not a state worth being able to configure.
             */
            String         vrn,
            List<String>   addressLines,
            String         contactLine,
            String         logoRef,
            String         logoDataUri,
            String         footerTerms,
            String         bankDetails
    ) {}

    /** A key-value meta pair printed in the document header (e.g. "Invoice No.: INV-0001"). */
    public record MetaPair(String label, String value) {}

    /** Bill-to / ship-to / supplier block. */
    public record PartyBlock(
            String       name,
            List<String> addressLines,
            String       taxId
    ) {}

    /**
     * A single document line. Price columns are nullable — a qty-only doc (the delivery note)
     * leaves unitPrice/discount/taxLabel/lineTotal null (D-5 / BR-DOC-07).
     *
     * <p>{@code sellingPrice} / {@code lastCost} / {@code marginPercent} are the buying-side trio the
     * vendor GRN prints (K9) and every other document type leaves null; the renderer drops a column
     * no line populates, so a null here costs nothing but a narrower table.
     */
    public record DocLine(
            int        lineNo,
            String     code,
            String     description,
            BigDecimal qty,
            String     unit,
            BigDecimal unitPrice,
            BigDecimal discount,
            String     taxLabel,
            BigDecimal lineTotal,
            BigDecimal sellingPrice,
            BigDecimal lastCost,
            BigDecimal marginPercent
    ) {
        /** The common case: a line with no buying-side comparison columns. */
        public DocLine(int lineNo, String code, String description, BigDecimal qty, String unit,
                       BigDecimal unitPrice, BigDecimal discount, String taxLabel,
                       BigDecimal lineTotal) {
            this(lineNo, code, description, qty, unit, unitPrice, discount, taxLabel, lineTotal,
                    null, null, null);
        }
    }

    /** Tax summary row (e.g. "VAT 18%", base, rate, vat amount). Empty for qty-only docs. */
    public record TaxRow(
            String     bandLabel,
            BigDecimal base,
            BigDecimal rate,
            BigDecimal vat
    ) {}

    /** Totals row (Net / VAT / Gross). Empty for qty-only docs. */
    public record TotalRow(
            String     label,
            BigDecimal amount,
            boolean    emphasised
    ) {}
}
