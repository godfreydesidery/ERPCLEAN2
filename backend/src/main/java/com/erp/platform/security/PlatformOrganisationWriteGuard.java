package com.erp.platform.security;

import com.erp.platform.common.api.ConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The platform organisation holds no business data (owner, 2026-11-07). While a request runs IN
 * the platform organisation - the operator at home, not inside an entered tenant - every write
 * outside platform administration is refused: sales, POS, stock, products, parties, GL, payroll,
 * and also new companies and branches.
 *
 * <p>An interceptor, so it runs before the request body is read: the refusal is the same for every
 * blocked endpoint, whatever was sent, and {@code PlatformOrganisationIT} sweeps every write
 * mapping against it. Reads are not blocked - there is nothing to read - and a request inside an
 * entered tenant runs as that tenant, so it is never in the platform organisation.
 */
@Component
public class PlatformOrganisationWriteGuard implements HandlerInterceptor {

    /** Writes still allowed in the platform organisation: platform administration only. */
    static final List<String> ALLOWED_PREFIXES = List.of(
            "/api/v1/auth",
            "/api/v1/organisations",
            "/api/v1/users",
            "/api/v1/user-roles",
            "/api/v1/user-branches",
            "/api/v1/user-companies",
            "/api/v1/roles");

    static final String MESSAGE = "The platform organisation holds no business data. Enter a tenant "
            + "from Platform > Tenants to work on its data.";

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * Optional so web-layer test slices, which load interceptors but not the registry, still start;
     * the full application always has it.
     */
    private final org.springframework.beans.factory.ObjectProvider<PlatformOrganisationRegistry> registry;

    public PlatformOrganisationWriteGuard(
            org.springframework.beans.factory.ObjectProvider<PlatformOrganisationRegistry> registry) {
        this.registry = registry;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        if (READS.contains(request.getMethod())) {
            return true;
        }
        RequestContext.Principal principal = RequestContext.get();
        PlatformOrganisationRegistry r = registry.getIfAvailable();
        if (principal == null || r == null || !r.isPlatform(principal.organisationId())) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (isAllowed(path)) {
            return true;
        }
        throw new ConflictException(MESSAGE);
    }

    static boolean isAllowed(String path) {
        for (String p : ALLOWED_PREFIXES) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
