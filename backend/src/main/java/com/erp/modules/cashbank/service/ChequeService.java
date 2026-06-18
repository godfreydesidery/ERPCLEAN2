package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.ChequeDto;
import com.erp.modules.cashbank.domain.dto.RegisterChequeRequest;
import java.util.List;

public interface ChequeService {

    ChequeDto register(RegisterChequeRequest req);

    ChequeDto clear(String uid);

    ChequeDto cancel(String uid);

    /**
     * INBOUND only: transition ISSUED → DEPOSITED.
     * Records the bank-deposit timestamp. No GL posting (ADR-0016 D-5).
     */
    ChequeDto deposit(String uid);

    /**
     * INBOUND only: transition DEPOSITED → BOUNCED.
     * Records bounce timestamp and reason. Bounce-reversal GL posting is deferred (D-9 follow-up).
     */
    ChequeDto bounce(String uid, String bounceReason);

    ChequeDto getByUid(String uid);

    List<ChequeDto> listByCompany(Long companyId);
}
