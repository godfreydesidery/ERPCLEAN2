package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.DepreciationScheduleLineDto;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import java.util.List;

public interface DepreciationScheduleService {

    /**
     * Generate the full depreciation schedule for an asset at place-in-service.
     * schedule_version = 1; created at the start date's fiscal period.
     */
    void generate(FixedAsset asset, Long createdBy);

    /**
     * Regenerate the remaining unposted schedule lines after a revaluation.
     * Bumps schedule_version; prior version's unposted lines are superseded (new version
     * rows replace them); posted lines are never deleted.
     */
    void regenerate(FixedAsset asset, Long createdBy);

    /** List all schedule lines for an asset (all versions). */
    List<DepreciationScheduleLineDto> listByAsset(String assetUid);
}
