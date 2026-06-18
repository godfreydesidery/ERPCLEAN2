package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ArCreditNoteService {

    /**
     * Raise a credit note. Posts FULL contra to GL once at raise (DR Revenue/VAT / CR AR-control).
     * If the request targets an invoice, immediately auto-applies the full CN to that invoice
     * (raise + apply in one TX — existing callers see the same net end-state as before).
     * (FR-AR-14, OQ-AR-04, ADR-0040 D-6)
     */
    ArCreditNoteDto raise(RaiseCreditNoteRequest req);

    /**
     * Apply a previously UNAPPLIED or PARTIAL credit note to one or more open invoices.
     * Decrements invoice outstanding_amount, decrements CN unapplied_amount, updates status.
     * Posts NOTHING to GL except a realized-FX plug per allocation when rates differ.
     * SELECT FOR UPDATE enforces the Σ invariant (ADR-0040 D-6).
     */
    ArCreditNoteDto apply(ApplyCreditNoteRequest req);

    /**
     * Replace the current allocation set with a new one (delete-and-reinsert).
     * Restores outstanding on previously allocated invoices before re-applying.
     * Posts no AR/Revenue legs — only FX plug when rates differ (same as apply).
     */
    ArCreditNoteDto reapply(ApplyCreditNoteRequest req);

    ArCreditNoteDto getByUid(String uid);

    Page<ArCreditNoteDto> listByCompany(Long companyId, Pageable pageable);
}
