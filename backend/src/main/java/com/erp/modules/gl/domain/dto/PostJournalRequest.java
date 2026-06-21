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
        @NotNull(message = "lines must not be null (BR-GL-01 requires at least 2 lines)")
        @NotEmpty(message = "lines must not be empty (BR-GL-01 requires at least 2 lines)")
        @Valid
        List<PostJournalLineRequest> lines
) {}
