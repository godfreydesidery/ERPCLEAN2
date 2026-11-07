package com.erp.modules.sales.domain.enums;

/** A review-list item is open until a manager acknowledges it (ADR-0068 D-8). */
public enum OfflineReviewStatus {
    OPEN,
    ACKNOWLEDGED
}
