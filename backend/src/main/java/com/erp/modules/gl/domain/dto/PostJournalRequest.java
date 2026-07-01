package com.erp.modules.gl.domain.dto;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/** Request to post a manual journal entry (FR-GL-06). */
public record PostJournalRequest(
        @NotBlank(message = "companyUid is required")
        String companyUid,
        @NotNull(message = "postingDate is required")
        LocalDate postingDate,
        String description,
        JournalSourceType sourceType,
        String sourceRef,
        /** Must contain at least one element; bean validation also cascades into each line (#29). */
        @NotNull(message = "Journal lines are required — please provide at least two lines.")
        @NotEmpty(message = "Journal lines must not be empty — a journal entry requires at least two lines.")
        @Valid
        List<PostJournalLineRequest> lines
) {}
