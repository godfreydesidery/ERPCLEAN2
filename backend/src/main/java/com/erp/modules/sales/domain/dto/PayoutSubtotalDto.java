package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PosPayoutType;
import java.math.BigDecimal;

/**
 * Subtotal for one payout type within a POS session (X/Z-read breakdown).
 *
 * <p>Payouts are cash OUTFLOWS from the drawer, so every {@code amount} is positive and the whole
 * breakdown is <em>subtracted</em> from expected cash. The sum of {@code amount} across the
 * breakdown equals {@code totalPayoutsNetAmount} on the same read — the breakdown never drops or
 * double-counts a payout.
 *
 * <p>The breakdown always carries one row per {@link PosPayoutType}, in enum order, zero-filled
 * where the session recorded no payout of that type. That keeps a printed Z-read's layout stable
 * shift to shift and makes two reads of the same session byte-identical.
 *
 * <p>The types are REFUND (cash returned to a customer), PAID_OUT (drawer-to-safe drop / misc petty
 * payout) and EXPENSE (a categorised business expense paid out of the drawer). EXPENSE prints as its
 * own line: a Z-read that lumps a refund together with a paid-out expense tells the owner nothing
 * about what the shop spent, which is the defect K8/V94 closes.
 */
public record PayoutSubtotalDto(
        PosPayoutType payoutType,
        BigDecimal amount,
        Long count
) {}
