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
        String          voidLabel
) {

    /** Branding header block derived from DocumentBranding or companies fallback. */
    public record BrandingBlock(
            String         displayName,
            String         legalName,
            String         taxId,
            List<String>   addressLines,
            String         contactLine,
            String         logoRef,
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
     * A single document line. Price columns are nullable — qty-only docs (delivery/GRN)
     * leave unitPrice/discount/taxLabel/lineTotal null (D-5 / BR-DOC-07).
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
            BigDecimal lineTotal
    ) {}

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
