package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.enums.LandedCostBasis;
import com.erp.modules.purchases.domain.enums.LandedCostChargeType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;

public record CreateLandedCostRequest(
        @NotBlank String companyUid,
        @NotNull LandedCostBasis basis,
        String notes,
        /** GR uids that this landed cost covers. */
        @NotEmpty List<String> receiptUids,
        @NotEmpty @Valid List<ChargeRequest> charges
) {
    public record ChargeRequest(
            @NotNull LandedCostChargeType chargeType,
            @NotNull @Positive BigDecimal amount
    ) {}
}
