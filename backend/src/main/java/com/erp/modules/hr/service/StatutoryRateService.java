package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreatePayeBandSetRequest;
import com.erp.modules.hr.domain.dto.CreateStatutoryRateSetRequest;
import com.erp.modules.hr.domain.dto.PayeBandSetDto;
import com.erp.modules.hr.domain.dto.StatutoryRateSetDto;
import java.util.List;

public interface StatutoryRateService {

    PayeBandSetDto createPayeBandSet(CreatePayeBandSetRequest req);

    PayeBandSetDto getPayeBandSetByUid(String uid);

    List<PayeBandSetDto> listPayeBandSets(Long companyId);

    StatutoryRateSetDto createRateSet(CreateStatutoryRateSetRequest req);

    StatutoryRateSetDto getRateSetByUid(String uid);

    List<StatutoryRateSetDto> listRateSets(Long companyId);
}
