package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashCountDto;
import com.erp.modules.cashbank.domain.dto.OpenCashCountRequest;
import com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest;
import java.util.List;

/**
 * End-of-day cash count against a CASH till (ADR-0050 D-7 PR-A).
 * OPEN (expected derived) -&gt; COUNTED (denominations recorded) -&gt; RECONCILED (variance auto-posted).
 */
public interface CashCountService {

    /** Opens a new count; derives {@code expectedAmount} from the till's book balance as-of the date. */
    CashCountDto open(OpenCashCountRequest req);

    /** Replaces the denomination breakdown; recomputes {@code countedAmount}/{@code varianceAmount}. */
    CashCountDto recordDenominations(String uid, RecordDenominationsRequest req);

    /**
     * Posts a non-zero variance to GL (+ a linked cash-book row) and marks RECONCILED.
     * Idempotent: a second call on an already-RECONCILED count is a no-op.
     */
    CashCountDto reconcile(String uid);

    CashCountDto getByUid(String uid);

    List<CashCountDto> listByAccount(Long companyId, Long accountId);
}
