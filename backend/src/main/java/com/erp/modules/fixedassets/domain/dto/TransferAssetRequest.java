package com.erp.modules.fixedassets.domain.dto;

/** Transfer an asset to a different branch/location — no GL effect (ADR-0030 D-6f). */
public record TransferAssetRequest(
        Long branchId,
        String location,
        Long costCentreId
) {}
