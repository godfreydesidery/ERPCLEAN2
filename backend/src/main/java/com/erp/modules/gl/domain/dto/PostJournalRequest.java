package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.time.LocalDate;
import java.util.List;

/** Request to post a manual journal entry (FR-GL-06). */
public record PostJournalRequest(
        String companyUid,
        LocalDate postingDate,
        String description,
        JournalSourceType sourceType,
        String sourceRef,
        List<PostJournalLineRequest> lines
) {}
