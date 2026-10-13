package com.erp.modules.hr.service;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.hr.domain.dto.RecordStatutoryPaymentRequest;
import com.erp.modules.hr.domain.dto.StatutoryLiabilityBalanceDto;
import java.util.List;

/**
 * ACC-07: paying payroll statutory liabilities (PAYE, NSSF, WCF, SDL, HESLB) to the authorities.
 * Their control accounts refuse manual journals and direct cash entries by design, so this is the
 * only way the books can record the payment.
 */
public interface PayrollStatutoryPaymentService {

    /** Outstanding balance per statutory liability for the caller's company. */
    List<StatutoryLiabilityBalanceDto> outstanding();

    /**
     * Pay part or all of one liability: DR its control account / CR the chosen cash/bank account.
     * Refuses an amount above what the ledger says is still owed.
     */
    CashTransactionDto pay(RecordStatutoryPaymentRequest req);
}
