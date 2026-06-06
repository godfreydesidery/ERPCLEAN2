package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.MeResponse;
import com.erp.modules.iam.domain.dto.TokenResponse;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.RefreshToken;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.RefreshTokenRepository;
import com.erp.platform.security.PermissionResolver;
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
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final JwtProperties jwtProps;
    private final LoginAttemptService loginAttempts;
    private final PermissionResolver permissionResolver;

    /** A bcrypt hash of a random value, computed once, for the constant-time unknown-user path (G3). */
    private final String dummyHash;

    public AuthServiceImpl(AppUserRepository users,
                           RefreshTokenRepository refreshTokens,
                           BranchRepository branches,
                           CompanyRepository companies,
                           PasswordEncoder passwordEncoder,
                           JwtService jwtService,
                           JwtProperties jwtProps,
                           LoginAttemptService loginAttempts,
                           PermissionResolver permissionResolver) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.branches = branches;
        this.companies = companies;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.jwtProps = jwtProps;
        this.loginAttempts = loginAttempts;
        this.permissionResolver = permissionResolver;
        this.dummyHash = passwordEncoder.encode(Tokens.newRefreshToken());
    }

    @Override
    public TokenResponse login(String username, String rawPassword) {
        Instant now = Instant.now();
        AppUser user = users.findByUsername(username == null ? null : username.toLowerCase())
                .orElse(null);

        if (user == null) {
            // Equalise timing for unknown usernames so an attacker can't distinguish "no such user"
            // from "wrong password" by response time (G3). Hash against a per-startup decoy of a
            // random value, then fail generically.
            passwordEncoder.matches(rawPassword, dummyHash);
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

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            // Persist the failed-attempt/lockout bookkeeping in a SEPARATE transaction so it
            // survives the rollback caused by the exception we throw next (LoginAttemptService).
            loginAttempts.recordFailure(user.getId(), now);
            throw AuthenticationException.invalidCredentials();
        }

        loginAttempts.recordSuccess(user.getId(), now);
        return issueSession(user);
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
                permissions);
    }

    /** Resolve active scope, issue the access JWT, persist a fresh refresh token, build the response. */
    private TokenResponse issueSession(AppUser user) {
        Optional<Branch> activeBranch = Optional.ofNullable(user.getDefaultBranchId())
                .flatMap(branches::findById);
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
                activeBranch.isPresent());

        return new TokenResponse(
                access.value(),
                access.expiresAt().getEpochSecond(),
                rawRefresh,
                authUser);
    }
}
