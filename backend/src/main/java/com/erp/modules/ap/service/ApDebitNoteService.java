package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.ApplyDebitNoteRequest;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApDebitNoteService {

    /**
     * Raise a debit note. Posts FULL contra ONCE (DR AP / CR Purchases [+ CR VAT_INPUT]) at the
     * DN's rate (ADR-0041 D3, mirror AR credit-note). If a bill is targeted, auto-applies the full
     * amount immediately so existing callers see the same net end-state. AP.DEBITNOTE.
     */
    ApDebitNoteDto raise(RaiseDebitNoteRequest req);

    /**
     * Apply an existing debit note to one or more open bills (ADR-0041 D3). Sub-ledger move only —
     * relieves bill outstanding + decrements DN unapplied; posts ONLY the realized-FX plug per
     * allocation when the bill rate differs from the DN rate.
     */
    ApDebitNoteDto apply(ApplyDebitNoteRequest req);

    /** Restore current allocations, then apply a fresh set (ADR-0041 D3). */
    ApDebitNoteDto reapply(ApplyDebitNoteRequest req);

    ApDebitNoteDto getByUid(String uid);

    Page<ApDebitNoteDto> listByCompany(Long companyId, Pageable pageable);

    Page<ApDebitNoteDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable);
}
