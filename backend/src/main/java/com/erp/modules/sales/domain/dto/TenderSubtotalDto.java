package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.TenderType;
import java.math.BigDecimal;

/**
 * Net subtotal for one tender type within a POS session (X/Z-read breakdown).
 *
 * <p>{@code amount} nets CASH over-tender change ({@code amount − change_amount}, BR-SALES-07);
 * a no-op for non-CASH tenders since only CASH ever carries a non-zero change_amount.
 *
 * <p>Introduced as part of the busy-day-simulation fix that stopped card/mobile-money/cheque
 * tenders from being folded into the cash-drawer expected-cash arithmetic
 * ({@link com.erp.modules.sales.service.PosSessionServiceImpl}).
 */
public record TenderSubtotalDto(
        TenderType tenderType,
        BigDecimal amount
) {}
