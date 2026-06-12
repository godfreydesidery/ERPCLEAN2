package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreatePayComponentRequest;
import com.erp.modules.hr.domain.dto.PayComponentDto;
import java.util.List;

public interface PayComponentService {

    PayComponentDto create(CreatePayComponentRequest req);

    PayComponentDto getByUid(String uid);

    List<PayComponentDto> listByCompany(Long companyId);

    PayComponentDto update(String uid, CreatePayComponentRequest req);

    void deactivate(String uid);
}
