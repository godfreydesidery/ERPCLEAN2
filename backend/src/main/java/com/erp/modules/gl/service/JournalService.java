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
     *
     * @param originalEntryUid the uid of the journal entry to reverse
     * @param reversalDate     the business date for the reversing entry; defaults to today if null
     * @param reason           optional free-text reason; incorporated into the reversing entry's
     *                         description for audit purposes
     */
    JournalEntryDto postManualReversal(String originalEntryUid, java.time.LocalDate reversalDate,
                                       String reason);
}
