package com.erp.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Catch-all for any {@code /api/**} path that has no real controller mapping.
 *
 * <p>Without this, an unmatched {@code GET /api/something-unknown} is swallowed by the SPA
 * resource handler ({@link SpaWebConfig}), which returns {@code null} for {@code api/} paths but
 * does not raise {@link NoHandlerFoundException}. Spring then falls through to the Whitelabel
 * {@code /error} path, leaking a stack trace (security defects RBAC-SECURITY-NEGATIVE-017/-046).
 *
 * <p>This controller is the last resort for {@code /api/**}: because Spring resolves
 * {@code @RequestMapping} in specificity order, all real API controllers (with more specific
 * paths) are preferred. Only truly unmatched requests reach here, where we explicitly raise
 * {@link NoHandlerFoundException} so the {@code GlobalExceptionHandler} can wrap it in the
 * standard {@code ApiResponse} 404 envelope with no stack trace.
 *
 * <p>Accessible to anonymous callers (path is unmatched → no controller → security cannot apply a
 * method-level guard, and returning 404 rather than 401 for unmatched paths does not aid
 * enumeration — the caller already knows the path exists or doesn't).
 */
@RestController
public class ApiNotFoundController {

    /**
     * Catch-all for every HTTP method on every unmatched {@code /api/**} sub-path. Spring
     * resolves more-specific {@code @RequestMapping}s first, so this only fires when no real
     * controller claimed the path.
     */
    @RequestMapping("/api/**")
    public void apiCatchAll(HttpServletRequest request) throws NoHandlerFoundException {
        throw new NoHandlerFoundException(
                request.getMethod(),
                request.getRequestURI(),
                org.springframework.http.HttpHeaders.EMPTY);
    }
}
