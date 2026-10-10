package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashTillOptionDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.UpdateCashBankAccountRequest;
import java.util.List;

public interface CashBankAccountService {

    CashBankAccountDto create(CreateCashBankAccountRequest req);

    CashBankAccountDto update(String uid, UpdateCashBankAccountRequest req);

    CashBankAccountDto setDefault(String uid);

    CashBankAccountDto getByUid(String uid);

    List<CashBankAccountDto> listByCompany(Long companyId);

    /**
     * The company's ACTIVE CASH-type accounts as a narrow picker list (no balances or bank detail),
     * for the cash-count till dropdown (LRB-03).
     */
    List<CashTillOptionDto> listCashTills(Long companyId);
}
