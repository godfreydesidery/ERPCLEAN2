package com.erp.modules.fx.domain.exception;

import com.erp.platform.common.money.CurrencyCode;

/**
 * Typed, user-safe exception: the requested currency is not enabled for the company/branch
 * scope (ADR-0039 D-7/D-8). Callers in Tranche 2 will catch this at document-creation time
 * to surface a clear validation error rather than a generic 500.
 */
public class CurrencyNotEnabledException extends RuntimeException {

    private final CurrencyCode currencyCode;
    private final Long         companyId;
    private final Long         branchId;

    public CurrencyNotEnabledException(CurrencyCode currencyCode, Long companyId, Long branchId) {
        super(buildMessage(currencyCode, companyId, branchId));
        this.currencyCode = currencyCode;
        this.companyId    = companyId;
        this.branchId     = branchId;
    }

    public CurrencyCode getCurrencyCode() { return currencyCode; }
    public Long         getCompanyId()    { return companyId; }
    public Long         getBranchId()     { return branchId; }

    private static String buildMessage(CurrencyCode code, Long companyId, Long branchId) {
        if (branchId != null) {
            return "Currency '" + code.value() + "' is not enabled for branch " + branchId
                    + " (company " + companyId + "). Enable it via CURRENCY.MANAGE.";
        }
        return "Currency '" + code.value() + "' is not enabled for company " + companyId
                + ". Enable it via CURRENCY.MANAGE.";
    }
}
