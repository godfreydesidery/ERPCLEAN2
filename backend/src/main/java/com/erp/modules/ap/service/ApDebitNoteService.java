package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApDebitNoteService {

    /** Raise a debit note. Posts DR AP / CR Purchases synchronously. AP.DEBITNOTE. */
    ApDebitNoteDto raise(RaiseDebitNoteRequest req);

    ApDebitNoteDto getByUid(String uid);

    Page<ApDebitNoteDto> listByCompany(Long companyId, Pageable pageable);

    Page<ApDebitNoteDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable);
}
