package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.UpdatePriceListRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PriceListService {

    PriceListDto create(CreatePriceListRequest req);

    PriceListDto getByUid(String uid);

    Page<PriceListDto> list(Long companyId, String q, Pageable pageable);

    PriceListDto updateByUid(String uid, UpdatePriceListRequest req);

    void archiveByUid(String uid);

    void restoreByUid(String uid);
}
