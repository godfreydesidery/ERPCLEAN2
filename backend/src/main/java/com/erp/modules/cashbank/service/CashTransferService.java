package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashTransferDto;
import com.erp.modules.cashbank.domain.dto.RecordTransferRequest;
import java.util.List;

public interface CashTransferService {

    CashTransferDto recordTransfer(RecordTransferRequest req);

    CashTransferDto getByUid(String uid);

    List<CashTransferDto> listByCompany(Long companyId);
}
