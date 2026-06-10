package com.erp.modules.tax.domain.dto;

/**
 * Result of a WHT capture — returned to the AR/AP service so it can include the WHT leg
 * in its GL entry (ADR-0017 D-9). The GL account id (not uid) is used for LineDraft construction.
 */
public record WhtCaptureResultDto(
        /** The GL account id for the WHT liability (WHT_PAYABLE) or receivable (WHT_RECEIVABLE). */
        Long glAccountId,
        String whtTransactionUid,
        String certificateNumber
) {}
