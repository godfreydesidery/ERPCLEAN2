package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import java.util.List;

public interface CashDirectEntryService {

    CashTransactionDto recordDirectEntry(RecordDirectEntryRequest req);

    CashTransactionDto getByUid(String uid);

    List<CashTransactionDto> listByAccount(Long companyId, Long accountId);
}
