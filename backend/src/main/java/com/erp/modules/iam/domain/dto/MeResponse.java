package com.erp.modules.iam.domain.dto;

import java.util.List;

/**
 * The current caller's identity + EFFECTIVE permission codes for their active scope (ADR-0001 D-E).
 * The web shell calls {@code GET /api/v1/auth/me} after login (and after a branch switch, Slice 5)
 * to drive permission-aware UI — hiding/disabling actions the user can't perform. The 403 gate is
 * still the real enforcement; this is convenience only. Root sees an {@code isRoot} flag rather than
 * an enumerated permission set (root bypasses scoping).
 */
public record MeResponse(
        String uid,
        String username,
        String displayName,
        boolean isRoot,
        String activeCompanyUid,
        String activeBranchUid,
        List<String> permissions) {
}
