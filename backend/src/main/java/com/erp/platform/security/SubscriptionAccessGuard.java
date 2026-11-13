package com.erp.platform.security;

import com.erp.modules.subscription.domain.enums.AddOn;
import com.erp.modules.subscription.domain.enums.SubscriptionStage;
import com.erp.modules.subscription.service.SubscriptionGate;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies the non-payment ladder and the OrbixHQ add-on to every API request (ADR-0069 D-6, D-9).
 *
 * <ul>
 *   <li><b>READ_ONLY</b> — every write is refused, the POS included (owner ruling: fully read-only).
 *       Still allowed: sign-in and session calls, rendering a document for a reprint, and the
 *       offline-sync upload, which keeps sales made BEFORE read-only began and holds the rest
 *       ({@code PosOfflineSyncServiceImpl}).
 *   <li><b>SUSPENDED</b> — every request is refused except the session calls. Sign-in itself is
 *       refused by {@code AuthServiceImpl}.
 *   <li><b>OrbixHQ</b> — a request carrying {@code X-Client-App: orbixhq} from a tenant without the
 *       add-on is refused. Apps before 1.4.2 do not send the header and are not gated (D-9).
 * </ul>
 *
 * <p>The platform operator ({@code is_root}, also inside an entered tenant), system principals and
 * tenants without a plan are never affected. An interceptor, like
 * {@link PlatformOrganisationWriteGuard}, so the refusal comes before the body is read and is the
 * same for every endpoint.
 */
@Component
public class SubscriptionAccessGuard implements HandlerInterceptor {

    static final String CLIENT_APP_HEADER = "X-Client-App";
    static final String ORBIXHQ_CLIENT = "orbixhq";

    static final String READ_ONLY_MESSAGE = "Your subscription is overdue, so the system is "
            + "read-only. Payment restores full access.";
    static final String SUSPENDED_MESSAGE = "Your subscription has lapsed and access is suspended. "
            + "Please contact your provider to restore it.";
    static final String ORBIXHQ_MESSAGE = "The OrbixHQ app is not part of your plan. Please "
            + "contact your provider to add it.";

    /** Session calls: always allowed, so a user can still sign out or see why they are blocked. */
    static final String AUTH_PREFIX = "/api/v1/auth";

    /** Writes still allowed while read-only. */
    static final List<String> READ_ONLY_ALLOWED_WRITES = List.of(
            AUTH_PREFIX,
            "/api/v1/documents/render",
            "/api/v1/pos/offline-sync");

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");

    /** Optional so web-layer test slices without the subscription module still start. */
    private final ObjectProvider<SubscriptionGate> gate;

    public SubscriptionAccessGuard(ObjectProvider<SubscriptionGate> gate) {
        this.gate = gate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        RequestContext.Principal principal = RequestContext.get();
        SubscriptionGate g = gate.getIfAvailable();
        if (g == null || principal == null || principal.root() || principal.system()
                || principal.organisationId() == null) {
            return true;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        Long org = principal.organisationId();

        if (ORBIXHQ_CLIENT.equalsIgnoreCase(request.getHeader(CLIENT_APP_HEADER))
                && g.entitlementOf(org).map(e -> !e.has(AddOn.ORBIXHQ)).orElse(false)) {
            throw new ForbiddenException(ORBIXHQ_MESSAGE);
        }

        SubscriptionStage stage = g.stageOf(org);
        if (stage == SubscriptionStage.SUSPENDED && !matches(path, List.of(AUTH_PREFIX))) {
            throw new ForbiddenException(SUSPENDED_MESSAGE);
        }
        if (stage == SubscriptionStage.READ_ONLY && !READS.contains(request.getMethod())
                && !matches(path, READ_ONLY_ALLOWED_WRITES)) {
            throw new ConflictException(READ_ONLY_MESSAGE);
        }
        return true;
    }

    static boolean matches(String path, List<String> prefixes) {
        for (String p : prefixes) {
            if (path.equals(p) || path.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }
}
