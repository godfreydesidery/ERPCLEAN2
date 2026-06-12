package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PayComponentBasis;
import com.erp.modules.hr.domain.enums.PayComponentKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreatePayComponentRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull PayComponentKind kind,
        @NotNull PayComponentBasis basis,
        @NotNull Long glAccountId,
        boolean taxable,
        boolean pensionable
) {}
