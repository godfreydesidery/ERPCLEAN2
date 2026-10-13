package com.erp.modules.sales.service;

import com.erp.modules.sales.repository.SalesInvoicePaymentRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ACC-05 / ARC-01: does a cash/bank account take sale tenders? Since a tender that names its
 * cash/bank account posts to that account's own GL link (not the company CASH mapping), such an
 * account receives sales takings in the GL that its cash book never sees — so Cash &amp; Bank must
 * refuse an end-of-day count on it, exactly as it already refuses one on the GL CASH account.
 * Company-scoped: the account id is matched only within {@code companyId}.
 */
@Component
public class SaleTenderAccountQuery {

    private final SalesInvoicePaymentRepository payments;

    public SaleTenderAccountQuery(SalesInvoicePaymentRepository payments) {
        this.payments = payments;
    }

    /** True when any sale tender in {@code companyId} names {@code cashBankAccountId}. */
    @Transactional(readOnly = true)
    public boolean takesSaleTenders(Long companyId, Long cashBankAccountId) {
        if (companyId == null || cashBankAccountId == null) {
            return false;
        }
        return payments.existsByCompanyIdAndCashBankAccountId(companyId, cashBankAccountId);
    }
}
