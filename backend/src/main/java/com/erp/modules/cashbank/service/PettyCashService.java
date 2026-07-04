package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CreatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.dto.PettyCashFundDto;
import com.erp.modules.cashbank.domain.dto.PettyCashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordPettyCashTxnRequest;
import com.erp.modules.cashbank.domain.dto.UpdatePettyCashFundRequest;
import java.util.List;

/**
 * Petty-cash imprest funds + their movements (ADR-0050 D-7 PR-B).
 *
 * <p>RECORD-ONLY this slice — no GL posting. A disbursement/replenishment/adjustment moves the
 * fund's balance and stamps {@code balanceAfter}; a captured {@code gl_account_id} is reserved for
 * a future manual journal or the GL fast-follow.
 */
public interface PettyCashService {

    PettyCashFundDto createFund(CreatePettyCashFundRequest req);

    PettyCashFundDto updateFund(String uid, UpdatePettyCashFundRequest req);

    /** Records a DISBURSEMENT/REPLENISHMENT/ADJUSTMENT against the fund; stamps {@code balanceAfter}. */
    PettyCashTransactionDto recordTransaction(String fundUid, RecordPettyCashTxnRequest req);

    PettyCashFundDto getFund(String uid);

    List<PettyCashFundDto> listFunds(Long companyId);

    List<PettyCashTransactionDto> listTransactions(String fundUid);
}
