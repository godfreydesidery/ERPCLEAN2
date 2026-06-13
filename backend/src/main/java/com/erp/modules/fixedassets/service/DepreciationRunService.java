package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.DepreciationRunDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunPreviewDto;
import com.erp.modules.fixedassets.domain.dto.RunDepreciationRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DepreciationRunService {

    /**
     * Preview the depreciation run for a period — read-only, nothing posted.
     * FA.DEPRECIATE + assertCanActIn.
     */
    DepreciationRunPreviewDto preview(Long companyId, String fiscalPeriodUid);

    /**
     * Post the depreciation run for a period (idempotent — returns existing if already posted).
     * FA.DEPRECIATE + assertCanActIn + period gate.
     */
    DepreciationRunDto post(RunDepreciationRequest req);

    DepreciationRunDto getByUid(String uid);

    Page<DepreciationRunDto> listByCompany(Long companyId, Pageable pageable);
}
