package com.erp.modules.hr.domain.dto;

import com.erp.modules.hr.domain.enums.PayComponentBasis;
import com.erp.modules.hr.domain.enums.PayComponentKind;

public record PayComponentDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        PayComponentKind kind,
        PayComponentBasis basis,
        Long glAccountId,
        boolean taxable,
        boolean pensionable,
        boolean active
) {}
