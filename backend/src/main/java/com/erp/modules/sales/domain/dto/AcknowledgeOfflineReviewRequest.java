package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Size;

/** A manager acknowledging an offline review item, with an optional note (ADR-0068 D-8). */
public record AcknowledgeOfflineReviewRequest(@Size(max = 500) String note) {}
