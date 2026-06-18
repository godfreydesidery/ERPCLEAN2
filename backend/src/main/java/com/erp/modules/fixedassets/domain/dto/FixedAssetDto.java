package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FixedAssetDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String assetNumber,
        Long categoryId,
        String name,
        FixedAssetStatus status,
        BigDecimal acquisitionCost,
        BigDecimal salvageValue,
        DepreciationMethod depreciationMethod,
        int lifePeriods,
        BigDecimal reducingRate,
        LocalDate acquisitionDate,
        LocalDate depreciationStartDate,
        BigDecimal carryingCost,
        BigDecimal accumulatedDepreciation,
        BigDecimal nbv,                         // derived: carryingCost - accumulatedDepreciation
        BigDecimal revaluationReserveBalance,
        Long supplierId,
        String sourceBillUid,
        String location,
        Long costCentreId,
        String assetTag,
        String serialNumber,
        String model,
        String manufacturer,
        String barcode,
        String capitalisedGlEntryUid,
        LocalDate disposedAt
) {}
