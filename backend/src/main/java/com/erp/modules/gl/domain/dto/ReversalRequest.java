package com.erp.modules.gl.domain.dto;

import java.time.LocalDate;

/**
 * Optional body for {@code POST /api/v1/gl/journals/uid/{uid}/reverse} (issue #30).
 *
 * <p>Both fields are optional:
 * <ul>
 *   <li>{@code reversalDate} — the business posting date for the reversing entry; defaults to
 *       today when absent.</li>
 *   <li>{@code reason} — free-text reason appended to the reversing entry's description so the
 *       audit trail explains why the reversal was made.</li>
 * </ul>
 */
public record ReversalRequest(
        LocalDate reversalDate,
        String reason
) {}
