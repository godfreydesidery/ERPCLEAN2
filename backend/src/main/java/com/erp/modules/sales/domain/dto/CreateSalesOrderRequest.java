package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateSalesOrderRequest(
        @NotBlank String companyUid,
        @NotBlank String customerUid,
        String agentUid,
        @NotBlank String currency,
        @NotNull LocalDate orderDate,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        String notes
) {}
