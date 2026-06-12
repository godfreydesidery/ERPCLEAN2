package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/** Request body for POST /api/v1/work-orders/uid/{uid}/issue (ADR-0035 D-5). */
public record IssueComponentsRequest(
        /**
         * true = issue the full planned quantity for all un-issued components (v1 default).
         * false = issue only the component uids listed in {@code componentUids}.
         */
        boolean full,
        /** Component line uids to issue; ignored when {@code full = true}. */
        List<String> componentUids,
        @NotNull LocalDate postingDate
) {}
