package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/** One waiter's paid bills over the period (J COB 2026-10-30). Money is in the base currency. */
public record WaiterSalesRowDto(
        String     waiterUid,
        String     waiterName,
        long       bills,
        long       guests,
        BigDecimal amount,
        BigDecimal averageBill) {}
