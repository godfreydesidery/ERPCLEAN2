package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.auth.AuthenticationException;
import com.erp.platform.security.config.SecurityProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manager step-up verification (see {@link StepUpAuthService}).
 *
 * <p><b>Why this is NOT a call into {@link AuthServiceImpl#login}:</b> login issues an access JWT and
 * a refresh token and the clients treat that response as "the session". Reusing it for a supervisor
 * override would swap the cashier's session for the manager's mid-sale. This service therefore has
 * no {@code JwtService} and no {@code RefreshTokenRepository} dependency at all — the "no token is
 * issued or rotated" property is structural, not a discipline rule, and is asserted by a unit test.
 *
 * <p><b>Lockout policy (deliberate divergence from login).</b> A failed step-up does NOT increment
 * the AUTHORISER's {@code failed_login_count} and can never lock their account. The person typing is
 * not the account holder, so counting failures there hands any cashier a one-button denial-of-service
 * against their own supervisor ("fumble the password five times, the manager can't log in for 15
 * minutes"). Instead:
 * <ul>
 *   <li>an EXISTING lock is still respected — a locked or disabled account cannot authorise anything;
 *   <li>brute-force is throttled against the CALLER (the operator's own account, which they can only
 *       hurt themselves with): after {@code erp.security.lockout.max-failed-attempts} credential
 *       failures the endpoint refuses for a short cooldown;
 *   <li>every attempt is audited with the caller as actor, so guessing is loud rather than silent.
 * </ul>
 *
 * <p><b>Nobody approves themselves.</b> Once the password verifies, the authoriser is compared with
 * the caller and a match is refused. This is not a nicety: the step-up is only a control if the
 * person asking cannot satisfy it alone, and several of the codes it is used with are ones the
 * operator legitimately holds for their own work ({@code POS.SESSION.RECONCILE} is on the CASHIER
 * bundle because a cashier must close their own shift). Without the comparison, retyping your own
 * password authorised your own action.
 *
 * <p><b>Disclosure.</b> Before the password is proven, every refusal returns one generic message —
 * unknown user, wrong password, disabled and locked are indistinguishable, so an operator cannot
 * enumerate accounts. Only AFTER the password verifies do we say "not authorised for this action",
 * which reveals nothing the password holder does not already have.
 */
@Service
@Transactional
public class StepUpAuthServiceImpl implements StepUpAuthService {

    /** {@code audit_log.target_type} for every step-up row — the authoriser's account. */
    private static final String TARGET_TYPE = "app_users";

    /** Shown for every pre-password refusal — must stay identical across all of them. */
    private static final String CREDENTIALS_MESSAGE =
            "Those details were not accepted. Check the username and password and try again.";

    /** Shown once the password is proven but the account lacks the requested authority. */
    private static final String NOT_AUTHORISED_MESSAGE =
            "That user is not allowed to approve this action.";

    /** Shown while the caller is in cooldown after repeated failures. */
    private static final String THROTTLED_MESSAGE =
            "Too many failed approval attempts. Please wait a moment and try again.";

    /**
     * How long a caller is refused after hitting the failure threshold. Short on purpose: a till that
     * cannot take an override is a stopped queue, and bcrypt at cost 12 already makes guessing slow.
     */
    private static final Duration COOLDOWN = Duration.ofSeconds(60);

    /** Failures older than this stop counting toward the threshold. */
    private static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);

    /** Opportunistic prune threshold so the throttle map cannot grow without bound. */
    private static final int PRUNE_ABOVE = 512;

    private final AppUserRepository users;
    private final PermissionRepository permissions;
    private final PasswordEncoder passwordEncoder;
    private final PermissionResolver permissionResolver;
    private final AuditService audit;
    private final SecurityProperties props;

    /** A bcrypt hash of a random value, computed once, for the constant-time unknown-user path. */
    private final String dummyHash;

    /** Per-caller failure counters. In-memory by design: no schema change, and it is a rate limit. */
    private final ConcurrentHashMap<Long, Throttle> throttles = new ConcurrentHashMap<>();

    public StepUpAuthServiceImpl(AppUserRepository users,
                                 PermissionRepository permissions,
                                 PasswordEncoder passwordEncoder,
                                 PermissionResolver permissionResolver,
                                 AuditService audit,
                                 SecurityProperties props) {
        this.users = users;
        this.permissions = permissions;
        this.passwordEncoder = passwordEncoder;
        this.permissionResolver = permissionResolver;
        this.audit = audit;
        this.props = props;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Override
    public AuthorityVerificationDto verifyAuthority(String username, String rawPassword,
                                                    String permissionCode) {
        RequestContext.Principal caller = RequestContext.get();
        if (caller == null || caller.userId() == null) {
            throw new AuthenticationException("Authentication is required.");
        }

        Instant now = Instant.now();
        String code = permissionCode == null ? "" : permissionCode.trim();
        String attemptedUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);

        if (isThrottled(caller.userId(), now)) {
            return refuse(code, attemptedUsername, null, "THROTTLED", THROTTLED_MESSAGE, false);
        }

        AppUser authoriser = users.findByUsername(attemptedUsername).orElse(null);

        if (authoriser == null) {
            // Equalise timing with the wrong-password path so response time can't enumerate users.
            passwordEncoder.matches(rawPassword, dummyHash);
            return refuse(code, attemptedUsername, null, "UNKNOWN_USER", CREDENTIALS_MESSAGE,
                    countAgainst(caller.userId(), now));
        }
        if (!authoriser.isActive() || authoriser.isLocked(now)) {
            // An existing lock IS honoured — step-up never bypasses it. Message stays generic:
            // telling a cashier "that account is locked" is an enumeration oracle.
            passwordEncoder.matches(rawPassword, dummyHash);
            return refuse(code, attemptedUsername, authoriser, "ACCOUNT_UNAVAILABLE",
                    CREDENTIALS_MESSAGE, countAgainst(caller.userId(), now));
        }
        if (!passwordEncoder.matches(rawPassword, authoriser.getPasswordHash())) {
            // NOTE: no authoriser.registerFailedLogin(...) here — see the class comment. The
            // authoriser's own lockout counter is untouched; the caller is throttled instead.
            return refuse(code, attemptedUsername, authoriser, "BAD_PASSWORD", CREDENTIALS_MESSAGE,
                    countAgainst(caller.userId(), now));
        }

        // --- password proven from here on ---

        // NOBODY MAY APPROVE THEMSELVES. Without this the whole control collapses wherever the
        // operator already holds the code being asked about — a cashier holds POS.SESSION.RECONCILE
        // (they need it to close their own shift), so retyping their own password answered
        // "Authorised by <themselves>" and the one action the step-up was added to protect was
        // unprotected. An over-the-shoulder approval involves a second person by definition, so this
        // is a property of step-up itself and belongs here rather than in each caller.
        //
        // Deliberately NOT a credential failure: the password was right, so it does not feed the
        // throttle, and it reuses the post-password message — a fumbled self-approval must look
        // exactly like "that user may not approve this", never like a hint to try someone else.
        if (authoriser.getId() != null && authoriser.getId().equals(caller.userId())) {
            return refuse(code, attemptedUsername, authoriser, "SELF_APPROVAL",
                    NOT_AUTHORISED_MESSAGE, false);
        }

        if (code.isEmpty() || permissions.findByCode(code).isEmpty()) {
            // An unseeded/typo'd code must never read as "approved" — including for root, whose
            // permission short-circuit would otherwise wave anything through.
            return refuse(code, attemptedUsername, authoriser, "UNKNOWN_PERMISSION",
                    NOT_AUTHORISED_MESSAGE, false);
        }

        // Resolve the AUTHORISER's authority in the CALLER's active scope: the override happens at
        // this till, in this branch. Scope comes from the request context, never from the body, so a
        // caller cannot nominate a company where their chosen manager happens to be privileged.
        // Root short-circuits inside hasPermission, exactly as everywhere else.
        RequestContext.Principal authoriserInCallerScope = new RequestContext.Principal(
                authoriser.getId(),
                authoriser.getUsername(),
                authoriser.isRoot(),
                caller.companyId(),
                caller.branchId(),
                caller.ip(),
                // The AUTHORISER's own tenant, not the caller's. P2-3 will compare the two and
                // refuse when they differ; today it is recorded so that check has something to
                // compare. Taking the caller's here would make the comparison vacuous.
                authoriser.getOrganisationId());
        boolean holds = permissionResolver.hasPermission(
                authoriserInCallerScope, code, now.toEpochMilli());

        if (!holds) {
            // Not a credential failure — the password was right — so it does not feed the throttle.
            return refuse(code, attemptedUsername, authoriser, "NO_AUTHORITY", NOT_AUTHORISED_MESSAGE,
                    false);
        }

        throttles.remove(caller.userId());
        audit.record(AuditEvent.of(AuditActions.AUTH_STEP_UP_SUCCESS, TARGET_TYPE,
                        authoriser.getId(), authoriser.getUid())
                .detail(auditDetail(code, attemptedUsername, "GRANTED")));

        return AuthorityVerificationDto.granted(
                code, authoriser.getUid(), authoriser.getUsername(), authoriser.getDisplayName());
    }

    /**
     * {@inheritDoc}
     *
     * <p>No password is involved (one was already proven, moments earlier, by
     * {@link #verifyAuthority}), so there is nothing here to guess and nothing to throttle. The
     * checks are the same three that run after the password there — existence/active, not-the-caller,
     * and genuinely-holds-the-code — deliberately in the same order and with the same messages.
     *
     * <p>Audited as a step-up outcome in its own right: an approval that is fabricated or names
     * somebody who cannot approve is exactly the event an investigation needs to find, and the
     * business transaction that asked the question is usually about to be rolled back, so it cannot
     * be left to the caller's own audit row.
     */
    @Override
    public AuthorityVerificationDto verifyAuthoriserUid(String authoriserUid, String permissionCode,
                                                        Long companyId) {
        RequestContext.Principal caller = RequestContext.get();
        if (caller == null || caller.userId() == null) {
            throw new AuthenticationException("Authentication is required.");
        }

        Instant now = Instant.now();
        String code = permissionCode == null ? "" : permissionCode.trim();
        String uid = authoriserUid == null ? "" : authoriserUid.trim();

        if (uid.isEmpty()) {
            return refuseUid(code, null, "NO_APPROVAL_SUPPLIED");
        }

        AppUser authoriser = users.findByUid(uid).orElse(null);
        if (authoriser == null || !authoriser.isActive() || authoriser.isLocked(now)) {
            // A uid the client invented, a leaver's account, a locked account — one answer for all
            // three. A till is not a place to enumerate who exists or who is suspended.
            return refuseUid(code, authoriser, "AUTHORISER_UNAVAILABLE");
        }
        if (authoriser.getId() != null && authoriser.getId().equals(caller.userId())) {
            return refuseUid(code, authoriser, "SELF_APPROVAL");
        }
        if (code.isEmpty() || permissions.findByCode(code).isEmpty()) {
            // An unseeded/typo'd code must never read as "approved" — including for root.
            return refuseUid(code, authoriser, "UNKNOWN_PERMISSION");
        }

        // Company comes from the LOADED document when the caller passed one (it does), so a request
        // can never nominate a company where its chosen approver happens to be privileged. The
        // branch stays the caller's active one — the till the action is happening at.
        RequestContext.Principal authoriserInScope = new RequestContext.Principal(
                authoriser.getId(),
                authoriser.getUsername(),
                authoriser.isRoot(),
                companyId != null ? companyId : caller.companyId(),
                caller.branchId(),
                caller.ip(),
                authoriser.getOrganisationId());  // the authoriser's tenant — see above
        if (!permissionResolver.hasPermission(authoriserInScope, code, now.toEpochMilli())) {
            return refuseUid(code, authoriser, "NO_AUTHORITY");
        }

        audit.record(AuditEvent.of(AuditActions.AUTH_STEP_UP_SUCCESS, TARGET_TYPE,
                        authoriser.getId(), authoriser.getUid())
                .detail(auditDetail(code, authoriser.getUsername(), "REVERIFIED")));

        return AuthorityVerificationDto.granted(
                code, authoriser.getUid(), authoriser.getUsername(), authoriser.getDisplayName());
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    /**
     * Audit + deny for the uid re-verification path. Written with {@code recordIndependent} because
     * the caller is about to roll its own transaction back on the refusal, and an audit row written
     * inside that transaction would roll back with it — losing precisely the record of the attempt.
     */
    private AuthorityVerificationDto refuseUid(String code, AppUser authoriser, String reason) {
        String username = authoriser == null ? "" : authoriser.getUsername();
        AuditEvent event = authoriser == null
                ? AuditEvent.of(AuditActions.AUTH_STEP_UP_FAIL, TARGET_TYPE)
                : AuditEvent.of(AuditActions.AUTH_STEP_UP_FAIL, TARGET_TYPE,
                        authoriser.getId(), authoriser.getUid());
        audit.recordIndependent(event.detail(auditDetail(code, username, reason)));
        return AuthorityVerificationDto.denied(code, NOT_AUTHORISED_MESSAGE);
    }

    /**
     * Audit the refusal and build the response. {@code authoriser} may be null (unknown username);
     * the audit row then carries no target row, only the attempted username in detail — mirroring
     * the unknown-user login path.
     */
    private AuthorityVerificationDto refuse(String code,
                                            String attemptedUsername,
                                            AppUser authoriser,
                                            String reason,
                                            String message,
                                            boolean throttleCounted) {
        Map<String, Object> detail = auditDetail(code, attemptedUsername, reason);
        detail.put("throttleCounted", throttleCounted);
        AuditEvent event = authoriser == null
                ? AuditEvent.of(AuditActions.AUTH_STEP_UP_FAIL, TARGET_TYPE)
                : AuditEvent.of(AuditActions.AUTH_STEP_UP_FAIL, TARGET_TYPE,
                        authoriser.getId(), authoriser.getUid());
        audit.record(event.detail(detail));
        return AuthorityVerificationDto.denied(code, message);
    }

    /** Mutable map (the caller adds a key); {@code Map.of} would also reject a null value. */
    private Map<String, Object> auditDetail(String code, String attemptedUsername, String outcome) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("permissionCode", code);
        detail.put("authoriserUsername", attemptedUsername);
        detail.put("outcome", outcome);
        return detail;
    }

    private boolean isThrottled(Long callerUserId, Instant now) {
        Throttle t = throttles.get(callerUserId);
        return t != null && t.blockedUntil() != null && t.blockedUntil().isAfter(now);
    }

    /**
     * Register a credential failure against the CALLER and report whether it counted. Only failures
     * that could be a password guess feed this — "right password, no authority" does not.
     */
    private boolean countAgainst(Long callerUserId, Instant now) {
        int maxAttempts = props.lockout().maxFailedAttempts();
        throttles.compute(callerUserId, (key, existing) -> {
            int failures = (existing == null || !existing.expiresAt().isAfter(now))
                    ? 1
                    : existing.failures() + 1;
            Instant blockedUntil = failures >= maxAttempts ? now.plus(COOLDOWN) : null;
            return new Throttle(failures, blockedUntil, now.plus(FAILURE_WINDOW));
        });
        if (throttles.size() > PRUNE_ABOVE) {
            throttles.values().removeIf(t -> !t.expiresAt().isAfter(now));
        }
        return true;
    }

    /** Per-caller rate-limit state. {@code blockedUntil} is null until the threshold is reached. */
    private record Throttle(int failures, Instant blockedUntil, Instant expiresAt) {
    }
}
