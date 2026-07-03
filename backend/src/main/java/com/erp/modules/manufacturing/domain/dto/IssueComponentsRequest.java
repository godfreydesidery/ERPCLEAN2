package com.erp.modules.manufacturing.domain.dto;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/** Request body for POST /api/v1/work-orders/uid/{uid}/issue-components (ADR-0035 D-5). */
public record IssueComponentsRequest(
        /**
         * true = issue the full remaining-planned quantity for all un-issued components (v1
         * default). Ignored when {@code lines} is non-empty — see {@link #lines()}.
         */
        boolean full,
        /**
         * Component line uids to issue at remaining-planned quantity; ignored when
         * {@code full = true} or {@code lines} is non-empty.
         */
        List<String> componentUids,
        @NotNull LocalDate postingDate,
        /**
         * Per-line actual-issue quantities (feat/production-actual-issue-qty): the production
         * supervisor records the real raw-material quantity consumed instead of the remaining
         * planned quantity. When this list is non-empty it takes precedence over {@code full}
         * and {@code componentUids} — each named component line is issued exactly its requested
         * {@code qty} (over-issue beyond remaining planned is allowed; it flows to WIP and
         * surfaces as a material variance at close). Null/absent preserves the pre-existing
         * remaining-planned behaviour driven by {@code full}/{@code componentUids}.
         */
        List<IssueComponentLine> lines
) {}
