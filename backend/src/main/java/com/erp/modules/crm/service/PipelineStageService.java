package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.CreatePipelineStageRequest;
import com.erp.modules.crm.domain.dto.PipelineStageDto;
import com.erp.modules.crm.domain.dto.UpdatePipelineStageRequest;
import java.util.List;

public interface PipelineStageService {

    PipelineStageDto create(CreatePipelineStageRequest request);

    PipelineStageDto getByUid(String uid);

    List<PipelineStageDto> listByCompany(Long companyId);

    PipelineStageDto updateByUid(String uid, UpdatePipelineStageRequest request);

    void deactivateByUid(String uid);
}
