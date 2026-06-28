package com.erp.modules.iam.domain.dto;

import java.util.List;

/**
 * Advisory report for one screen where a role can open the screen (holds the accessPermission)
 * but is missing one or more required, closure-bearing reads the screen fires on load (ADR-0047
 * Grant-time validator section).
 *
 * <p>Emitted only when {@code missingReads} is non-empty. Absence from the response means
 * the role's grant set is fully closed over this screen's required reads, OR the role does not
 * hold the screen's {@code accessPermission} at all (screen not in scope for this role).
 *
 * <p>This is a read-only advisory DTO — it never gates a grant or modifies state.
 *
 * @param screen            manifest screen identifier (e.g. {@code ar.record-receipt})
 * @param accessPermission  the permission code that opens the screen (route-guard / primary action)
 * @param missingReads      codes for required, closure-bearing ({@code has}/{@code scoped}) reads
 *                          the role lacks; never empty when this DTO is returned
 */
public record ScreenReadGapDto(
        String screen,
        String accessPermission,
        List<String> missingReads) {}
