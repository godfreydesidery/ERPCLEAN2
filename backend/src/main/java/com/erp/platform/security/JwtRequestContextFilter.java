package com.erp.platform.security;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.config.SecurityErrorResponder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.MDC;
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
 *
 * <p><b>MDC / correlation-id (ADR-0038 D-2).</b> Every request gets a {@code requestId} — taken
 * from the {@code X-Request-Id} header if present and non-blank, otherwise generated as a random
 * UUID. Authenticated requests also push {@code userId}, {@code username}, {@code companyId}, and
 * {@code branchId} into the SLF4J MDC so every log line for the request is traceable to a user and
 * tenant. The {@code X-Request-Id} is echoed in the response header for client-side correlation.
 * MDC is cleared in the {@code finally} block to prevent cross-request leakage on pooled threads.
 *
 * <p><b>Known limitation:</b> {@code @Async} outbox dispatcher and {@code @Scheduled} poller run on
 * threads separate from the request thread. MDC context (requestId, companyId, etc.) does NOT
 * propagate to async event handlers — log lines from GL/stock handlers triggered by an outbox event
 * will have empty MDC fields. This is acceptable at current scale; full trace propagation requires a
 * distributed-tracing bridge (deferred, ADR-0038 D-Fenced #9).
 */
@Component
public class JwtRequestContextFilter extends OncePerRequestFilter {

    static final String BRANCH_OVERRIDE_HEADER = "X-Branch-Uid";
    static final String REQUEST_ID_HEADER      = "X-Request-Id";

    private final BranchRepository branches;
    private final UserBranchRepository userBranches;
    private final AppUserRepository appUsers;
    private final SecurityErrorResponder errorResponder;

    public JwtRequestContextFilter(BranchRepository branches, UserBranchRepository userBranches,
                                   AppUserRepository appUsers,
                                   SecurityErrorResponder errorResponder) {
        this.branches = branches;
        this.userBranches = userBranches;
        this.appUsers = appUsers;
        this.errorResponder = errorResponder;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // D-2 (ADR-0038): generate or accept a correlation id for every request — authenticated or not.
        String requestId = Optional.ofNullable(request.getHeader(REQUEST_ID_HEADER))
                .filter(s -> !s.isBlank())
                .orElseGet(() -> UUID.randomUUID().toString());
        MDC.put("requestId", requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

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
                    principal = resolvePrincipal(jwt, request.getHeader(BRANCH_OVERRIDE_HEADER),
                            request.getRemoteAddr());
                } catch (AccessDeniedException denied) {
                    errorResponder.handle(request, response, denied);
                    return; // do NOT continue the chain — the 403 envelope is already written
                }

                // F9 (ADR-0004 D-8): re-check the user is still ACTIVE on every request — a
                // disabled user is rejected (401) immediately rather than after the JWT TTL.
                // One PK lookup — same order as ADR-0002's accepted per-request read.
                if (!appUsers.existsByIdAndStatus(principal.userId(),
                        com.erp.platform.common.domain.MasterStatus.ACTIVE)) {
                    errorResponder.commence(request, response,
                            new org.springframework.security.core.AuthenticationException(
                                    "User account is no longer active.") {});
                    return;
                }

                RequestContext.set(principal);

                // D-2: enrich MDC with tenant context so every log line carries user + tenant ids.
                MDC.put("userId",    String.valueOf(principal.userId()));
                MDC.put("username",  principal.username());
                MDC.put("companyId", String.valueOf(principal.companyId()));
                MDC.put("branchId",  String.valueOf(principal.branchId()));
            }
            chain.doFilter(request, response);
        } finally {
            // D-2: clear MDC before the thread returns to the pool — must not leak across requests.
            MDC.clear();
            RequestContext.clear();
        }
    }

    /**
     * Build the principal, applying the X-Branch-Uid override when present (else the JWT default).
     * IP is threaded in from the request for audit trail population (ADR-0004 D-4).
     */
    private RequestContext.Principal resolvePrincipal(Jwt jwt, String overrideUid, String ip) {
        Long userId = parseLong(jwt.getSubject());
        String username = jwt.getClaimAsString("username");
        boolean root = Boolean.TRUE.equals(jwt.getClaim("isRoot"));

        if (overrideUid == null || overrideUid.isBlank()) {
            // No override — keep the default scope minted at login.
            return new RequestContext.Principal(userId, username, root,
                    parseLong(jwt.getClaimAsString("companyId")),
                    parseLong(jwt.getClaimAsString("branchId")),
                    ip);
        }

        // Override present: resolve + validate. Fail closed (403) on any defect.
        // F8 (ADR-0004 D-8): a branch under a non-ACTIVE company is not usable — reject it.
        Branch branch = branches.findWithCompanyByUid(overrideUid)
                .filter(Branch::isUsableForSession)
                .orElseThrow(() -> new AccessDeniedException("Branch not available."));

        if (!root && userBranches.findByUserIdAndBranchId(userId, branch.getId()).isEmpty()) {
            // A non-root caller may only switch to a branch they are actively assigned to (D-F).
            throw new AccessDeniedException("You are not assigned to that branch.");
        }

        return new RequestContext.Principal(
                userId, username, root, branch.getCompany().getId(), branch.getId(), ip);
    }

    private static Long parseLong(String s) {
        return (s == null || s.isBlank()) ? null : Long.valueOf(s);
    }
}
