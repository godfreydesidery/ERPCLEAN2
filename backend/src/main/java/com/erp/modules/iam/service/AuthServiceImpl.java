package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.MeResponse;
import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.RefreshToken;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.password.PasswordPolicy;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.auth.AuthenticationException;
import com.erp.platform.security.auth.Tokens;
import com.erp.platform.security.config.JwtProperties;
import com.erp.platform.security.jwt.JwtService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core auth logic (ARCHITECTURE §4). Login verifies credentials with constant-ish handling (generic
 * error, no enumeration), applies lockout, and issues an access JWT + a rotated refresh token.
 * Refresh is single-use: presenting a consumed token is treated as theft and revokes the whole
 * user's tokens. Logout revokes the presented refresh token.
 */
@Service
@Transactional
public class AuthServiceImpl implements AuthService {

    private final AppUserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final BranchRepository branches;
    private final CompanyRepository companies;
    private final UserBranchRepository userBranches;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;
    private final LoginAttemptService loginAttempts;
    private final PermissionResolver permissionResolver;
    private final OrganisationRepository organisations;

    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;

    /** A bcrypt hash of a random value, computed once, for the constant-time unknown-user path (G3). */
    private final String dummyHash;

    public AuthServiceImpl(AppUserRepository users,
                           RefreshTokenRepository refreshTokens,
                           BranchRepository branches,
                           CompanyRepository companies,
                           UserBranchRepository userBranches,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           JwtProperties jwtProps,
                           LoginAttemptService loginAttempts,
                           PermissionResolver permissionResolver,
                           OrganisationRepository organisations,
                           PasswordPolicy passwordPolicy,
                           AuditService audit) {
        this.organisations = organisations;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.branches = branches;
        this.companies = companies;
        this.userBranches = userBranches;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
        this.loginAttempts = loginAttempts;
        this.permissionResolver = permissionResolver;
        this.dummyHash = passwordEncoder.encode(Tokens.newRefreshToken());
    }

    @Override
    public TokenResponse login(String username, String rawPassword, String ip) {
        Instant now = Instant.now();
        AppUser user = findLoginUser(username).orElse(null);

        if (user == null) {
            // Equalise timing for unknown usernames so an attacker can't distinguish "no such user"
            // from "wrong password" by response time (G3). Hash against a per-startup decoy of a
            // random value, then fail generically.
            passwordEncoder.matches(rawPassword, dummyHash);
            // Audit the unknown-username attempt in its own REQUIRES_NEW tx (ADR-0004 D-3).
            loginAttempts.recordUnknownUserFailure(username, ip, now);
            throw AuthenticationException.invalidCredentials();
        }

        if (!user.isActive()) {
            // Disabled accounts cannot log in. Generic message — don't disclose the reason.
            throw AuthenticationException.invalidCredentials();
        }
        if (user.isLocked(now)) {
            throw new AuthenticationException(
                    "Account is locked. Try again later or contact an administrator.");
        }
        assertTenantIsOpen(user);

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // Persist the failed-attempt/lockout bookkeeping in a SEPARATE transaction so it
            // survives the rollback caused by the exception we throw next (LoginAttemptService).
            loginAttempts.recordFailure(user.getId(), ip, now);
            throw AuthenticationException.invalidCredentials();
        }

        loginAttempts.recordSuccess(user.getId(), ip, now);
        return issueSession(user);
    }

    /**
     * The account a sign-in name refers to (LUI-16).
     *
     * <p>An exact match always wins — that is every full {@code name@organisation} and every bare
     * name issued before usernames were suffixed (platform operators, pre-1.8.0 accounts), so no
     * existing sign-in changes meaning.
     *
     * <p>Otherwise, a name typed WITHOUT an {@code @} is read as {@code name@alias} only when the
     * installation hosts exactly one customer organisation. Then the suffix carries no information
     * — it is the same for every member of staff — and asking a clerk to type it on every login
     * only produces lock-outs. On a shared (multi-customer) instance the bare name is NOT resolved,
     * even when it happens to be unique across organisations: resolving it there would make one
     * tenant's sign-ins depend on another tenant's user list (a second {@code john} created
     * elsewhere would silently break this {@code john}'s login), and would let a bare-name guess
     * reach into whichever tenant holds the name. The resolved account then goes through exactly
     * the same password, lockout, active and tenant-open checks, and an unresolved name takes the
     * same constant-time "invalid credentials" path as any unknown user, so nothing about which
     * names exist is revealed.
     */
    private java.util.Optional<AppUser> findLoginUser(String username) {
        if (username == null) {
            return java.util.Optional.empty();
        }
        String normalised = username.toLowerCase();
        java.util.Optional<AppUser> exact = users.findByUsername(normalised);
        if (exact.isPresent()) {
            return exact;
        }
        String local = normalised.trim();
        if (local.isEmpty() || local.indexOf('@') >= 0) {
            return java.util.Optional.empty();
        }
        java.util.List<String> aliases = organisations.findCustomerAliases();
        if (aliases.size() != 1) {
            return java.util.Optional.empty();
        }
        return users.findByUsername(local + "@" + aliases.get(0));
    }

    @Override
    public TokenResponse refresh(String rawRefreshToken) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokens.findByTokenHash(Tokens.hash(rawRefreshToken))
                .orElseThrow(() -> new AuthenticationException("Invalid refresh token."));

        if (token.isConsumed()) {
            // Reuse of a rotated/revoked token = likely theft. Revoke the whole chain (all the
            // user's tokens) in a committing transaction so it survives the exception, then refuse.
            // Fail closed.
            loginAttempts.revokeAllTokens(token.getUserId(), now);
            throw new AuthenticationException("Refresh token already used. Please sign in again.");
        }
        if (!token.isActive(now)) {
            throw new AuthenticationException("Refresh token expired. Please sign in again.");
        }

        AppUser user = users.findById(token.getUserId())
                .orElseThrow(() -> new AuthenticationException("Invalid refresh token."));
        if (!user.isActive()) {
            throw AuthenticationException.invalidCredentials();
        }

        // Rotate: mint a successor, mark the old one rotated and pointing at it.
        TokenResponse response = issueSession(user);
        // The successor's hash is the last-created token for this user; re-read to link the chain.
        refreshTokens.findByTokenHash(Tokens.hash(response.refreshToken()))
                .ifPresent(successor -> token.markRotated(successor.getId(), now));
        return response;
    }

    @Override
    public void logout(String rawRefreshToken) {
        refreshTokens.findByTokenHash(Tokens.hash(rawRefreshToken))
                .ifPresent(t -> t.revoke(Instant.now()));
    }

    @Override
    @Transactional(readOnly = true)
    public MeResponse me() {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.userId() == null) {
            throw new AuthenticationException("Authentication is required.");
        }
        AppUser user = users.findById(principal.userId())
                .orElseThrow(AuthenticationException::invalidCredentials);

        // Effective permission codes for the active scope. Root bypasses scoping, so it carries no
        // enumerated set — the client keys off isRoot for "can do anything" (D-E).
        List<String> permissions = user.isRoot()
                ? List.of()
                : List.copyOf(permissionResolver.resolve(
                        principal.userId(), principal.companyId(), principal.branchId(),
                        System.currentTimeMillis()));

        String companyUid = Optional.ofNullable(principal.companyId())
                .flatMap(companies::findById).map(c -> c.getUid()).orElse(null);
        String branchUid = Optional.ofNullable(principal.branchId())
                .flatMap(branches::findById).map(Branch::getUid).orElse(null);

        return new MeResponse(
                user.getUid(),
                user.getUsername(),
                user.getDisplayName(),
                user.isRoot(),
                companyUid,
                branchUid,
                permissions,
                user.isMustChangePassword());
    }

    /**
     * Self-service password change (ADM-02 / PAR-14).
     *
     * <p>Same controls as every other password write: the current password must match (a wrong
     * one counts towards the login lockout exactly like a failed sign-in, so this endpoint cannot
     * be used to guess it), the new one goes through {@link PasswordPolicy} and bcrypt, and the
     * change is audited without the password or its hash. Every refresh token the user holds is
     * revoked in this transaction, so a session on another device ends at its next refresh; the
     * caller gets a fresh session back. Clears {@code must_change_password}.
     */
    @Override
    public TokenResponse changeOwnPassword(String currentPassword, String newPassword, String ip) {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.userId() == null) {
            throw new AuthenticationException("Authentication is required.");
        }
        AppUser user = users.findScopedById(principal.userId()) // id from the verified JWT
                .orElseThrow(AuthenticationException::invalidCredentials);
        Instant now = Instant.now();
        if (!user.isActive()) {
            throw AuthenticationException.invalidCredentials();
        }
        if (user.isLocked(now)) {
            throw new AuthenticationException(
                    "Account is locked. Try again later or contact an administrator.");
        }
        if (currentPassword == null
                || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            // Separate REQUIRES_NEW tx so the lockout bookkeeping survives this request's rollback.
            loginAttempts.recordFailure(user.getId(), ip, now);
            throw new IllegalArgumentException("Your current password is not correct.");
        }
        if (currentPassword.equals(newPassword)) {
            throw new IllegalArgumentException(
                    "Choose a new password that is different from your current one.");
        }
        passwordPolicy.validate(newPassword);

        user.changePassword(passwordEncoder.encode(newPassword), now);
        user.setMustChangePassword(false);
        users.save(user);
        // End every other session (bulk update runs before the new token below is inserted).
        refreshTokens.revokeAllForUser(user.getId(), now);

        // D-6: NEVER log the password or its hash.
        audit.record(AuditEvent.of(AuditActions.USER_PASSWORD_SET, "app_users",
                        user.getId(), user.getUid())
                .detail(java.util.Map.of("self", "true")));
        return issueSession(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserBranchDto> myBranches() {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.userId() == null) {
            throw new AuthenticationException("Authentication is required.");
        }
        AppUser user = users.findById(principal.userId())
                .orElseThrow(AuthenticationException::invalidCredentials);
        // Only ACTIVE branches are switchable — don't offer a branch the user can't actually enter.
        return userBranches.findByUserIdOrderByAssignedAtAscIdAsc(user.getId()).stream()
                .filter(ub -> ub.getBranch().getStatus() == com.erp.platform.common.domain.MasterStatus.ACTIVE)
                .map(ub -> UserBranchDto.from(ub, user.getUid()))
                .toList();
    }

    /** Resolve active scope, issue the access JWT, persist a fresh refresh token, build the response. */
    private TokenResponse issueSession(AppUser user) {
        // The default branch scopes the session. F8 (ADR-0004 D-8): use isUsableForSession() so a
        // branch under an ARCHIVED company is also excluded — not just an archived branch itself.
        // Such a user lands with no active branch (read-only) until an admin assigns a live default.
        Optional<Branch> activeBranch = userBranches.findByUserIdAndIsDefaultTrue(user.getId())
                .map(UserBranch::getBranch)
                .filter(Branch::isUsableForSession);
        Long companyId = activeBranch.map(b -> b.getCompany().getId()).orElse(null);
        Long branchId = activeBranch.map(Branch::getId).orElse(null);

        JwtService.IssuedToken access = jwtService.issueAccessToken(user, companyId, branchId);

        String rawRefresh = Tokens.newRefreshToken();
        Instant expires = Instant.now().plus(jwtProps.refreshTokenTtlDays(), ChronoUnit.DAYS);
        RefreshToken refresh = new RefreshToken(user.getId(), Tokens.hash(rawRefresh), expires);
        refresh.setClientScope(companyId, branchId);
        refreshTokens.save(refresh);

        TokenResponse.AuthUser authUser = new TokenResponse.AuthUser(
                user.getUid(),
                user.getUsername(),
                user.getDisplayName(),
                user.isRoot(),
                activeBranch.map(b -> b.getCompany().getUid()).orElse(null),
                activeBranch.map(Branch::getUid).orElse(null),
                activeBranch.isPresent(),
                user.isMustChangePassword());

        return new TokenResponse(
                access.value(),
                access.expiresAt().getEpochSecond(),
                rawRefresh,
                authUser);
    }

    /**
     * P2-4 (ADR-0062): a user whose ORGANISATION is not active cannot log in, however good their
     * password is. Without this, suspending a tenant would mean disabling their accounts one by
     * one, and re-enabling them would mean remembering which ones were already disabled.
     *
     * <p>The message is deliberately distinct from a credential failure. A generic "invalid
     * username or password" here would send a cashier to reset a password that is perfectly good,
     * and their own office is the only place that can actually help them. It still says nothing
     * about plans, billing or amounts - that is between the vendor and whoever signs the invoice,
     * not something to render on a till.
     *
     * <p>A user with no organisation is let through: on a single-tenant install that is simply a
     * row the backfill has not reached, and refusing there would lock people out of a system that
     * has no tenancy to enforce yet.
     */
    private void assertTenantIsOpen(AppUser user) {
        if (user.getOrganisationId() == null) {
            return;
        }
        // Root is exempt, and this is a recovery lever rather than a loophole. Suspension is a
        // commercial control over a CUSTOMER's staff; if it also locked out root there would be
        // nobody left who could resume the tenant, because `resume` itself requires a signed-in
        // caller. Independent of the self-suspend guard in OrganisationServiceImpl - that one stops
        // the mistake, this one survives it however it was made (a direct UPDATE, a restored
        // backup, a future admin screen).
        if (user.isRoot()) {
            return;
        }
        organisations.findScopedById(user.getOrganisationId())
                .filter(o -> o.getStatus() != MasterStatus.ACTIVE)
                .ifPresent(o -> {
                    throw new AuthenticationException(
                            "This account is not available at the moment. "
                            + "Please contact your administrator.");
                });
    }
}
