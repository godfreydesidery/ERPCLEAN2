package com.erp.modules.purchases.domain.enums;

/**
 * How the unit costs entered on this company's goods receipts should be READ when the printed
 * Goods Received Note derives its VAT check figure (Kilimanjaro 2026-09-12 #3, ADR-0063).
 *
 * <p><b>This never affects posting.</b> A goods receipt posts no VAT — purchase VAT belongs to the
 * supplier bill and is posted from there — and stock is valued at the stored line cost whichever
 * value is chosen here. This decides one thing: how the foot of a printed document is derived.
 *
 * <p>The defect it exists to fix: the note used to compute {@code total = net + VAT}
 * unconditionally, so a cost typed off a supplier invoice that already contained VAT had 18% added
 * to a figure that already had it. There was no way to say otherwise — {@code price_includes_vat}
 * exists only on sales price lists.
 */
public enum PurchaseVatTreatment {

    /**
     * Costs are net of VAT; the note adds VAT on top. The behaviour every receipt has had until
     * now, and the default, so no existing company changes when this ships.
     *
     * <p>{@code net = Σ line amounts}, {@code vat = net × rate}, {@code total = net + vat}
     */
    EXCLUSIVE,

    /**
     * Costs already contain VAT; the note EXTRACTS it rather than adding it, so the printed total
     * is the figure that was entered — which is what the supplier's invoice says.
     *
     * <p>{@code net = amount ÷ (1 + rate)}, {@code vat = amount − net}, {@code total = amount}
     */
    INCLUSIVE,

    /**
     * Show no VAT band at all. For a business that is not VAT registered: a band of zeros invites
     * the reader to wonder what is missing, and "we do not charge or reclaim it" is a different
     * answer from "it is zero this time".
     *
     * <p>{@code net = Σ line amounts}, no bands, {@code total = net}
     */
    NONE;

    /** Never null: an unreadable or absent stored value falls back to today's behaviour. */
    public static PurchaseVatTreatment orDefault(String raw) {
        if (raw == null || raw.isBlank()) {
            return EXCLUSIVE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return EXCLUSIVE;
        }
    }
}
