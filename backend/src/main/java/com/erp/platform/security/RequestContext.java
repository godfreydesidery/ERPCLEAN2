package com.erp.platform.security;

/**
 * The current request's authenticated context (ARCHITECTURE §5): who is calling and the active
 * company/branch. Populated by {@code RequestContextFilter} from the JWT (+ branch-override header,
 * Slice 5). Request-scoped — held in a {@link ThreadLocal} so services can read it without
 * threading it through every method.
 *
 * <p>Values are ids; null company/branch means "no active scope" (e.g. a user with no branch).
 */
public final class RequestContext {

    /**
     * @param userId         the authenticated user, or {@code null} for a SYSTEM principal — see below.
     * @param organisationId the tenant, derived from {@code app_users.organisation_id} AFTER
     *                       authentication. Never taken from caller input, and never parsed out of
     *                       the username (ADR-0062). {@code null} on a SYSTEM principal, and — until
     *                       the constraining migration lands — possibly null on a real user whose row
     *                       predates the backfill.
     *
     * <p><b>{@code userId == null} is what marks a SYSTEM principal, not a null organisation.</b>
     * The outbox handlers establish scope for the posting engine with
     * {@code Principal(null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null)} —
     * eighteen of them, none passing a real user id. Tenancy checks must exempt on that, because it
     * is structural and unreachable from a request.
     *
     * <p>They must <b>not</b> exempt on a null {@code organisationId}: a real user can legitimately
     * have one, so exempting there would hand every such account an unscoped session through a data
     * gap rather than an authorisation decision. A real user whose tenant cannot be established
     * fails closed. (ADR-0062; MULTITENANCY-PLAN.md P2.5-1.)
     */
    public record Principal(Long userId, String username, boolean root, Long companyId, Long branchId,
                            String ip, Long organisationId) {

        /** True for the synthetic principal the outbox handlers install — see the class javadoc. */
        public boolean system() {
            return userId == null;
        }

        /**
         * The synthetic principal an outbox handler installs so the posting engine's ScopeGuard
         * checks have a scope to run against. Deliberately carries no user and no organisation:
         * it is not acting for a tenant, it is replaying a committed event whose company is
         * already fixed.
         *
         * <p>Named rather than inlined so the eighteen call sites read as intentional, and so
         * "which principals are exempt from tenancy checks" is answerable by finding the callers
         * of one method instead of grepping for a null first argument.
         */
        public static Principal system(Long companyId, Long branchId) {
            return new Principal(null, "SYSTEM", false, companyId, branchId, null, null);
        }

        /**
         * Six-argument form retained so the existing test fixtures keep compiling; it leaves the
         * organisation null.
         *
         * <p><b>Production code must not use this.</b> A principal built here is indistinguishable
         * from one whose tenant genuinely could not be resolved. Use the canonical constructor and
         * pass the organisation explicitly — {@code null} only where the principal really is SYSTEM.
         */
        public Principal(Long userId, String username, boolean root, Long companyId, Long branchId, String ip) {
            this(userId, username, root, companyId, branchId, ip, null);
        }
    }

    private static final ThreadLocal<Principal> CURRENT = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void set(Principal principal) {
        CURRENT.set(principal);
    }

    public static Principal get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
