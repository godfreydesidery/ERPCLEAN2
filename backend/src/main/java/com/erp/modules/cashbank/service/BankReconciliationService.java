package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.BankReconciliationDto;
import com.erp.modules.cashbank.domain.dto.MarkClearedRequest;
import com.erp.modules.cashbank.domain.dto.OpenReconciliationRequest;
import java.util.List;

public interface BankReconciliationService {

    BankReconciliationDto open(OpenReconciliationRequest req);

    BankReconciliationDto markCleared(String reconciliationUid, MarkClearedRequest req);

    BankReconciliationDto complete(String reconciliationUid);

    BankReconciliationDto getByUid(String uid);

    List<BankReconciliationDto> listByAccount(Long companyId, Long accountId);
}
