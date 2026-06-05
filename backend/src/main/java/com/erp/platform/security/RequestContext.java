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

    public record Principal(Long userId, String username, boolean root, Long companyId, Long branchId) {
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
