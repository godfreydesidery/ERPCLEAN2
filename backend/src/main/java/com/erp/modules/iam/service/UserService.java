package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import java.util.List;

/**
 * User administration (DATA-MODEL §1.4) — minimal lifecycle pulled forward to support branch
 * assignment (Slice 4). Create / list / get / update contact details / disable / enable / unlock /
 * admin set-password. {@code is_root} is bootstrap-only and is never settable through this service.
 * Controllers depend on this interface (DIP).
 */
public interface UserService {

    UserDto create(CreateUserRequest request);

    UserDto getByUid(String uid);

    /**
     * Company-scoped list (tenant-isolation fix, security audit 2026-06-25). Root sees everyone;
     * a non-root caller sees only users who share their active company; null company → empty list
     * (fail-closed). The pick-target for the org-wide picker is {@link #listOrgWide()}.
     */
    List<UserDto> list();

    /**
     * Org-wide list — bypasses the company scope so the assign-role picker can show users NOT yet
     * in the caller's company. Gated by USER.MANAGE at the controller; never call from a low-trust
     * path.
     */
    List<UserDto> listOrgWide();

    UserDto updateByUid(String uid, UpdateUserRequest request);

    /** Disable a user (status INACTIVE) — they can no longer log in. A root user cannot be disabled. */
    void disableByUid(String uid);

    /** Re-enable a disabled user (status ACTIVE). */
    void enableByUid(String uid);

    /** Clear a lockout so a locked-out user can sign in again (FR-IAM-09). */
    void unlockByUid(String uid);

    /** Admin-driven password reset (FR-IAM-10) — validated against the password policy. */
    void setPasswordByUid(String uid, SetPasswordRequest request);
}
