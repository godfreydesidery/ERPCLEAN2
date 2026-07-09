package com.erp.platform.security;

import com.erp.platform.common.api.ForbiddenException;
import java.util.Collection;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The VERTICAL authorization guard (ADR-0059) — the missing sibling of {@link ScopeGuard}. Where
 * {@code ScopeGuard} answers the horizontal/tenant question ("may the caller act in company X?"),
 * this answers the vertical/privilege question: <b>may the caller confer THIS much authority?</b>
 *
 * <p>The single invariant: a non-root caller may only confer authority that is a <b>subset</b> of
 * their own effective permissions in their active scope. On top of that sits a <b>reserved floor</b>:
 * the reserved "power-to-delegate" permissions ({@link #RESERVED}) may be conferred only by root or a
 * caller who holds every one of them (org-admin-tier), so a delegated admin can hand out operational
 * powers but never the power to administer users or roles.
 *
 * <p>Root ({@code is_root}) is exempt everywhere, consistent with {@link PermissionResolver} and
 * ADR-0001 D-E. The caller's effective set is resolved by the same {@link PermissionResolver} the
 * {@code @perm} gate uses, so the ceiling can never diverge from what the caller can actually do. It
 * fails closed: a caller with no active company / no resolved permissions can confer nothing.
 *
 * <p>Called from the three authority-conferring boundaries — {@code UserRoleServiceImpl.grant}
 * (assign a role), {@code RoleServiceImpl.setPermissions} (author what a role confers), and
 * {@code UserServiceImpl.setPasswordByUid} (take over an account) — after their existing
 * scope/membership checks. This component is entity-free (operates on permission-code strings only)
 * so it crosses no module boundary, mirroring {@code PermissionResolver}.
 */
@Component
public class AuthorityCeiling {

    /**
     * Reserved, non-delegable "power-to-delegate" permission codes. A non-root caller may confer any
     * of these only if they hold EVERY one (i.e. are org-admin-tier). Kept in code (not the seed) so
     * a tenant cannot edit it; anchored to the {@code iam}-module codes in
     * {@code R__seed_permissions.sql}. See {@code DefaultRoleBundlesSeededTest} — no shipped
     * operational bundle may carry any of these.
     */
    static final Set<String> RESERVED = Set.of(
            "USER.MANAGE",          // create/update/disable/unlock users; set passwords
            "USER.COMPANY.MANAGE",  // assign/remove company memberships (a grant prerequisite)
            "ROLE.MANAGE",          // grant/revoke role assignments
            "ROLE.ADMIN",           // author the shared org-wide role catalogue
            "BRANCH.ASSIGN");       // assign users to branches (branch-scope weaponisation)

    private final PermissionResolver permissionResolver;

    public AuthorityCeiling(PermissionResolver permissionResolver) {
        this.permissionResolver = permissionResolver;
    }

    /** Whether conferring {@code codes} confers at least one reserved (power-to-delegate) permission. */
    public static boolean isPrivileged(Collection<String> codes) {
        if (codes == null) {
            return false;
        }
        for (String code : codes) {
            if (RESERVED.contains(code)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Assert the caller may confer every code in {@code codes}. Root is exempt. Otherwise:
     * <ol>
     *   <li><b>subset</b> — every code must be in the caller's effective permission set; you cannot
     *       confer a permission you do not hold;</li>
     *   <li><b>reserved floor</b> — if any code is reserved, the caller must hold ALL reserved codes.</li>
     * </ol>
     * Conferring nothing is always allowed. Throws {@link ForbiddenException} (HTTP 403) on violation;
     * the message never names the offending code (error-hygiene rule).
     */
    public void assertCanConfer(RequestContext.Principal principal, Collection<String> codes) {
        if (principal != null && principal.root()) {
            return; // root bypasses vertical scoping (ADR-0001 D-E)
        }
        if (codes == null || codes.isEmpty()) {
            return; // conferring nothing is always permitted
        }
        Set<String> callerCodes = callerEffectivePermissions(principal);
        if (!callerCodes.containsAll(codes)) {
            throw ForbiddenException.notPermitted(); // (1) subset
        }
        if (isPrivileged(codes) && !callerCodes.containsAll(RESERVED)) {
            throw ForbiddenException.notPermitted(); // (2) reserved floor
        }
    }

    /**
     * As {@link #assertCanConfer} but for granting/authoring a ROLE. A non-root caller may never
     * confer a {@code is_system} role (its authority is out of reach by definition — this blocks
     * granting {@code ORG_ADMIN} with a clear, defence-in-depth failure). Otherwise the role's
     * permission codes go through the subset + reserved-floor checks.
     */
    public void assertCanConferRole(RequestContext.Principal principal, boolean roleIsSystem,
                                    Collection<String> roleCodes) {
        if (principal != null && principal.root()) {
            return;
        }
        if (roleIsSystem) {
            throw ForbiddenException.notPermitted(); // only root may grant a system role (e.g. ORG_ADMIN)
        }
        assertCanConfer(principal, roleCodes);
    }

    private Set<String> callerEffectivePermissions(RequestContext.Principal principal) {
        if (principal == null) {
            return Set.of();
        }
        return permissionResolver.resolve(
                principal.userId(), principal.companyId(), principal.branchId(), System.currentTimeMillis());
    }
}
