package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ArCreditNoteService {

    /** Raise a standalone credit note (FR-AR-14, OQ-AR-04). Posts to GL synchronously. */
    ArCreditNoteDto raise(RaiseCreditNoteRequest req);

    ArCreditNoteDto getByUid(String uid);

    Page<ArCreditNoteDto> listByCompany(Long companyId, Pageable pageable);
}
