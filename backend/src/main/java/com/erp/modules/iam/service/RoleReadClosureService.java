package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.ScreenReadGapDto;
import java.util.List;

/**
 * Advisory read-closure validator for a role's grant set (ADR-0047 Grant-time validator).
 *
 * <p>Computes, for each manifest screen that the role can open (i.e. the role holds the screen's
 * {@code accessPermission}), the set of required, closure-bearing reads that the role lacks.
 * Returns one {@link ScreenReadGapDto} per screen with a non-empty gap. An empty result means
 * the role is fully closed over all the screens it can operate.
 *
 * <p>This is ADVISORY ONLY. Results inform the administrator; they do not block grant operations.
 * Reads are performed without altering any state.
 */
public interface RoleReadClosureService {

    /**
     * Returns the read-closure gaps for the role identified by {@code roleUid}.
     *
     * <p>A gap is: screen {@code s} where the role holds {@code s.accessPermission} AND at least one
     * read in {@code s.reads} has {@code necessity=required}, {@code gate ∈ {has, scoped}}, and the
     * role lacks the read's {@code code}.
     *
     * @param roleUid the role's external UID
     * @return advisory gap list; empty if the role's grants are closed over all operable screens
     */
    List<ScreenReadGapDto> gapsForRole(String roleUid);
}
