package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import java.util.List;

public interface CashDirectEntryService {

    CashTransactionDto recordDirectEntry(RecordDirectEntryRequest req);

    /**
     * A direct entry raised by ANOTHER MODULE's posting flow (payroll net-wages disbursement), whose
     * counter account the module resolved from its own gl_config rather than a user choosing it.
     * Same as {@link #recordDirectEntry} except the user-facing counter-account guards
     * (allowManualPosting / control-account) are not applied — a system settlement of a control
     * account is exactly what those guards would otherwise refuse. Not exposed over REST.
     *
     * @param branchId the branch the entry belongs to (e.g. the payroll run's), or null to use the
     *                 caller's session branch
     */
    CashTransactionDto recordSystemEntry(RecordDirectEntryRequest req, Long branchId);

    CashTransactionDto getByUid(String uid);

    List<CashTransactionDto> listByAccount(Long companyId, Long accountId);
}
