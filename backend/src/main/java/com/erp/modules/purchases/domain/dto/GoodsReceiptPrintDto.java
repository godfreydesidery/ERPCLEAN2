package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything the printed vendor Goods Received Note needs, resolved in one place (Kilimanjaro K9,
 * 2026-08-12).
 *
 * <p><b>Why a separate DTO from {@link GoodsReceiptDto}.</b> Two reasons. First, the printed note
 * carries figures the receipt itself does not store — selling price, previous cost, margin, expected
 * VAT — and BR-DOC-02 / BR-DOC-09 forbid the documents module from deriving any of them, so they
 * have to be resolved on this side of the boundary. Second, selling price and margin have no
 * business on the general {@code /goods-receipts} read model: that endpoint is open to every
 * storekeeper who can receive stock, and quietly attaching the shop's margin to it would widen who
 * can see it far beyond who asked for it. This DTO is reachable only through the document renderer,
 * which is gated on {@code DOCUMENT.RENDER}.
 *
 * @param preparedByName the {@code received_by} user's display name — the "Prepared By" on the face
 *                       of the note. Blank when the receipt predates the field or the user is gone;
 *                       the printed line then falls back to a blank signature rule like the others.
 * @param netAmount      Σ line amounts — the goods value
 * @param vatAmount      Σ expected input VAT across the bands (see {@link GoodsReceiptVatBandDto})
 * @param roundingAmount always zero today; carried so the printed foot matches the layout the client
 *                       reconciles against, and so a future rounding policy has somewhere to land
 * @param totalAmount    {@code net + vat + rounding}
 */
public record GoodsReceiptPrintDto(
        String  uid,
        Long    companyId,
        String  receiptNumber,
        String  status,
        Instant receivedAt,
        String  purchaseOrderNumber,
        String  supplierName,
        String  supplierTin,
        List<String> supplierAddressLines,
        String  branchName,
        String  currency,
        String  notes,
        String  preparedByName,
        List<GoodsReceiptPrintLineDto> lines,
        List<GoodsReceiptVatBandDto>   vatBands,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal roundingAmount,
        BigDecimal totalAmount
) {}
