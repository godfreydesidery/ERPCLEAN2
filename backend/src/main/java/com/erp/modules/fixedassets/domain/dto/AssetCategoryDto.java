package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

public record AssetCategoryDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        DepreciationMethod defaultMethod,
        int defaultLifePeriods,
        BigDecimal defaultReducingRate,
        Long assetAccountId,
        Long accumDepAccountId,
        Long depExpenseAccountId,
        MasterStatus status
) {}
