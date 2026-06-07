package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UpdateUnitOfMeasureRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface UnitOfMeasureService {

    UnitOfMeasureDto create(CreateUnitOfMeasureRequest req);

    UnitOfMeasureDto getByUid(String uid);

    Page<UnitOfMeasureDto> list(Long companyId, String q, Pageable pageable);

    UnitOfMeasureDto updateByUid(String uid, UpdateUnitOfMeasureRequest req);

    void archiveByUid(String uid);

    void restoreByUid(String uid);
}
