package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.ChequeDto;
import com.erp.modules.cashbank.domain.dto.RegisterChequeRequest;
import java.util.List;

public interface ChequeService {

    ChequeDto register(RegisterChequeRequest req);

    ChequeDto clear(String uid);

    ChequeDto cancel(String uid);

    ChequeDto getByUid(String uid);

    List<ChequeDto> listByCompany(Long companyId);
}
