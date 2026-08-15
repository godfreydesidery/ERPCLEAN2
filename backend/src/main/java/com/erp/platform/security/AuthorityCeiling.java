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
    /**
     * Tier 3 (§1.2): the vendor's own roles. Never conferrable by a tenant, whatever they hold.
     *
     * <p>Named by code rather than derived from {@code is_system}, because every shipped bundle is
     * also {@code is_system} — that flag says "we ship it", not "only we may grant it". The two were
     * conflated before D-3, which is exactly what made the bundles ungrantable.
     */
    static final Set<String> PLATFORM_TIER_3 = Set.of("PLATFORM_OPERATOR");

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
        assertCanConferRole(principal, roleIsSystem, roleCodes, null);
    }

    /**
     * D-3 (ADR-0062 P4-2). Replaces the blanket "non-root may never confer an {@code is_system} role".
     *
     * <p>That rule made the twelve shipped bundles <b>decorative</b>: they are all {@code is_system},
     * so a tenant's own administrator could not grant a single one of them to their own staff. A
     * platform-wide role nobody in the organisation can confer is not a role, it is a decoration —
     * and the workaround people reach for is {@code setRoot(true)}, which is the sharpest risk in the
     * whole design.
     *
     * <p>The replacement is a tier rule, not a relaxation:
     * <ol>
     *   <li><b>Tier 3</b> ({@code PLATFORM_OPERATOR}) is never conferrable by a tenant, at any
     *       authority. It is the vendor's own role and it does not belong to a customer's ladder.
     *       Listed by code because the role does not exist yet (D-2 stage 2) and this must already
     *       refuse it on the day it does.</li>
     *   <li><b>Tiers 1 and 2</b> (the bundles, and {@code ORG_ADMIN}) go through the ordinary ceiling:
     *       you may confer only what you already hold, and the reserved floor still applies. ADR-0059's
     *       escalation guard is therefore <b>intact</b> — it is the only thing standing between
     *       {@code ROLE.MANAGE} and self-elevation, and this change does not touch it.</li>
     * </ol>
     *
     * <p>The grantee being inside the caller's own organisation is enforced by the caller
     * ({@code UserRoleServiceImpl}, P3-3) before this is reached, so it is not re-checked here.
     *
     * @param roleCode the role's code, used only for the tier-3 test; null skips it
     */
    public void assertCanConferRole(RequestContext.Principal principal, boolean roleIsSystem,
                                    Collection<String> roleCodes, String roleCode) {
        if (principal != null && principal.root()) {
            return;
        }
        if (roleCode != null && PLATFORM_TIER_3.contains(roleCode)) {
            throw ForbiddenException.notPermitted();
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
