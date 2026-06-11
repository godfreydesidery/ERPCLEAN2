package com.erp.modules.crm.domain.enums;

/**
 * Type of a CRM activity (ADR-0031 D-2).
 * TASK carries due_date + assignee_user_id + done flag.
 * The others (CALL/EMAIL/MEETING/NOTE) are historical records with no due date.
 */
public enum ActivityType {
    CALL,
    EMAIL,
    MEETING,
    NOTE,
    TASK
}
