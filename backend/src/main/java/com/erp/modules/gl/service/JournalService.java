package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.PostJournalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface JournalService {

    /** Post a manual balanced journal entry (FR-GL-06, GL.POST). */
    JournalEntryDto postManual(PostJournalRequest req);

    JournalEntryDto getByUid(String uid);

    Page<JournalEntryDto> list(Long companyId, Pageable pageable);

    /**
     * Post a manual reversing entry for the given original entry uid (BR-GL-11).
     * The reversal date defaults to today if null.
     */
    JournalEntryDto postManualReversal(String originalEntryUid, java.time.LocalDate reversalDate);
}
