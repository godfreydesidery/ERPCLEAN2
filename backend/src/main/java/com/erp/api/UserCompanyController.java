package com.erp.api;

import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
import com.erp.modules.iam.service.UserCompanyService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Explicit user↔company membership lifecycle: assign, remove, list (V77). Non-authoritative —
 * membership rows only ADD access; they never gate or strip it (roles + branches are the
 * authoritative access decisions). Scope enforcement (caller's active company == target company)
 * is delegated to {@link UserCompanyServiceImpl} via {@code ScopeGuard.assertCanActIn}.
 *
 * <p>Raw DTOs returned — {@code ApiResponseAdvice} wraps them in the {@code ApiResponse} envelope.
 */
@RestController
@RequestMapping("/api/v1/user-companies")
public class UserCompanyController {

    private final UserCompanyService userCompanyService;

    public UserCompanyController(UserCompanyService userCompanyService) {
        this.userCompanyService = userCompanyService;
    }

    /**
     * Explicitly assign a user to a company. Idempotent at the service layer (ensureMembership
     * is always called first), but this endpoint throws 409 on a duplicate active row (explicit
     * assign rejects duplicates so the caller can detect a mis-click).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('USER.COMPANY.MANAGE')")
    public UserCompanyDto assign(@Valid @RequestBody AssignUserCompanyRequest request) {
        return userCompanyService.assign(request);
    }

    /**
     * Soft-revoke a membership by its uid. Only the user_company row is affected — roles and
     * branch assignments are unaffected (non-authoritative phase, V77).
     */
    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('USER.COMPANY.MANAGE')")
    public void remove(@PathVariable String uid) {
        userCompanyService.remove(uid);
    }

    /**
     * Active company memberships for a user. Non-root callers see only the membership in their
     * active company (tenant-isolation, mirrors UserBranchController and UserRoleController).
     */
    @GetMapping
    @PreAuthorize("@perm.has('USER.VIEW')")
    public List<UserCompanyDto> listForUser(@RequestParam String userUid) {
        return userCompanyService.listForUser(userUid);
    }
}
