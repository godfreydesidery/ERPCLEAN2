package com.erp.platform.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Populates {@link RequestContext} from the validated JWT for the duration of the request, then
 * clears it (ARCHITECTURE §5). Runs after Spring Security has authenticated the bearer token, so the
 * principal is a {@link Jwt}. The branch-override header (X-Branch-Uid) is honoured in Slice 5.
 */
@Component
public class JwtRequestContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                RequestContext.set(toPrincipal(jwt));
            }
            chain.doFilter(request, response);
        } finally {
            RequestContext.clear();
        }
    }

    private RequestContext.Principal toPrincipal(Jwt jwt) {
        Long userId = parseLong(jwt.getSubject());
        String username = jwt.getClaimAsString("username");
        Boolean root = jwt.getClaim("isRoot");
        Long companyId = parseLong(jwt.getClaimAsString("companyId"));
        Long branchId = parseLong(jwt.getClaimAsString("branchId"));
        return new RequestContext.Principal(
                userId, username, Boolean.TRUE.equals(root), companyId, branchId);
    }

    private static Long parseLong(String s) {
        return (s == null || s.isBlank()) ? null : Long.valueOf(s);
    }
}
