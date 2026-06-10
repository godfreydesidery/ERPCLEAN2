package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import java.math.BigDecimal;

/**
 * VAT adjustment response DTO (ADR-0017 D-1 / D-2c).
 */
public record VatAdjustmentDto(
        Long id,
        String uid,
        Long vatReturnId,
        Long companyId,
        VatAdjustmentReason reason,
        VatAdjustmentSign sign,
        BigDecimal amount,
        String narrative
) {}
