package com.erp.platform.security;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.config.SecurityErrorResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link RequestContext} from the validated JWT for the duration of the request, then
 * clears it (ARCHITECTURE §5). Runs after the bearer token is authenticated, so the principal is a
 * {@link Jwt}.
 *
 * <p><b>Branch-switch override (ADR-0003).</b> The JWT scope (companyId/branchId minted at login) is
 * the DEFAULT. An optional {@code X-Branch-Uid} header switches the REQUEST scope without re-login or
 * a DB write: the header branch is resolved by uid and, for a non-root caller, must be an ACTIVE
 * {@code user_branch} assignment of theirs — otherwise {@link AccessDeniedException} → 403 (rendered
 * as the {@code ApiResponse} envelope by {@code SecurityErrorResponder} via the security
 * exception-translation path). Root may switch into any existing ACTIVE branch unchecked (D-4); a
 * non-existent or archived branch uid is still rejected for everyone (fail closed, D-3).
 */
@Component
public class JwtRequestContextFilter extends OncePerRequestFilter {

    static final String BRANCH_OVERRIDE_HEADER = "X-Branch-Uid";

    private final BranchRepository branches;
    private final UserBranchRepository userBranches;
    private final SecurityErrorResponder errorResponder;

    public JwtRequestContextFilter(BranchRepository branches, UserBranchRepository userBranches,
                                   SecurityErrorResponder errorResponder) {
        this.branches = branches;
        this.userBranches = userBranches;
        this.errorResponder = errorResponder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                // Build/validate the scope (incl. the X-Branch-Uid override). A rejected override
                // throws AccessDeniedException — render it HERE: this filter runs AFTER
                // ExceptionTranslationFilter in the chain, so a thrown exception would otherwise
                // escape the chain uncaught and never reach the accessDeniedHandler (ADR-0003 D-2
                // erratum). We render the same ApiResponse 403 envelope directly.
                RequestContext.Principal principal;
                try {
                    principal = resolvePrincipal(jwt, request.getHeader(BRANCH_OVERRIDE_HEADER));
                } catch (AccessDeniedException denied) {
                    errorResponder.handle(request, response, denied);
                    return; // do NOT continue the chain — the 403 envelope is already written
                }
                RequestContext.set(principal);
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    /** Build the principal, applying the X-Branch-Uid override when present (else the JWT default). */
    private RequestContext.Principal resolvePrincipal(Jwt jwt, String overrideUid) {
        Long userId = parseLong(jwt.getSubject());
        String username = jwt.getClaimAsString("username");
        boolean root = Boolean.TRUE.equals(jwt.getClaim("isRoot"));

        if (overrideUid == null || overrideUid.isBlank()) {
            // No override — keep the default scope minted at login.
            return new RequestContext.Principal(userId, username, root,
                    parseLong(jwt.getClaimAsString("companyId")),
                    parseLong(jwt.getClaimAsString("branchId")));
        }

        // Override present: resolve + validate. Fail closed (403) on any defect.
        Branch branch = branches.findByUid(overrideUid)
                .filter(b -> b.getStatus() == MasterStatus.ACTIVE)
                .orElseThrow(() -> new AccessDeniedException("Branch not available."));

        if (!root && userBranches.findByUserIdAndBranchId(userId, branch.getId()).isEmpty()) {
            // A non-root caller may only switch to a branch they are actively assigned to (D-F).
            throw new AccessDeniedException("You are not assigned to that branch.");
        }

        return new RequestContext.Principal(
                userId, username, root, branch.getCompany().getId(), branch.getId());
    }

    private static Long parseLong(String s) {
        return (s == null || s.isBlank()) ? null : Long.valueOf(s);
    }
}
