package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserCompany;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.AuthorityCeiling;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.password.PasswordPolicy;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * User administration (DATA-MODEL §1.4). Usernames are stored lowercased and are org-unique;
 * passwords go through {@link PasswordPolicy} + bcrypt. {@code is_root} is never set here — only
 * {@code BootstrapRunner} mints the root admin — so a created/updated user can never gain super-admin
 * via the API. Disabling a root user is refused (it could lock the deployment out of administration).
 *
 * <p><b>Tenant-isolation (security audit 2026-06-26).</b> All five mutators (update, disable,
 * enable, unlock, setPassword) now gate on the same company-membership check as {@link #getByUid}:
 * a non-root principal may only mutate users who are members of the principal's active company.
 * Out-of-scope and root-user targets both yield {@code 404} to avoid existence leakage.
 */
@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final AppUserRepository users;
    private final UserCompanyRepository userCompanies;
    private final UserRoleRepository userRoles;
    private final CompanyRepository companies;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final AuthorityCeiling authorityCeiling;

    public UserServiceImpl(AppUserRepository users,
                           UserCompanyRepository userCompanies,
                           UserRoleRepository userRoles,
                           CompanyRepository companies,
                           PasswordEncoder passwordEncoder,
                           PasswordPolicy passwordPolicy,
                           AuditService audit,
                           AuthorityCeiling authorityCeiling) {
        this.users = users;
        this.userCompanies = userCompanies;
        this.userRoles = userRoles;
        this.companies = companies;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.authorityCeiling = authorityCeiling;
    }

    @Override
    public UserDto create(CreateUserRequest request) {
        String username = request.username().toLowerCase();
        if (users.existsByUsername(username)) {
            throw new ConflictException("Username already exists: " + username);
        }
        passwordPolicy.validate(request.password());

        AppUser user = new AppUser(
                username, passwordEncoder.encode(request.password()), request.displayName());
        user.setEmail(request.email());
        user.setPhone(request.phone());
        // is_root stays false by default — never settable via the API.

        // The new user belongs to the CREATOR's tenant (ADR-0062 P2-1). Taken from the
        // authenticated principal, never from the request body: accepting a tenant from input is
        // exactly the caller-supplied scope this design exists to remove, and it would let an
        // administrator mint an account inside somebody else's organisation.
        RequestContext.Principal creator = RequestContext.get();
        user.setOrganisationId(creator != null ? creator.organisationId() : null);
        AppUser saved = users.save(user);

        audit.record(AuditEvent.of(AuditActions.USER_CREATE, "app_users", saved.getId(), saved.getUid())
                .detail(Map.of("username", saved.getUsername(), "displayName", saved.getDisplayName())));

        // ADR-0046 create-time analogue: a non-root admin can only create users within their own
        // company scope, so the created user must immediately BE a member of the creator's active
        // company — otherwise it belongs to no company and is invisible in the creator's (company-
        // scoped) list(), while a re-create trips "Username already exists" (F-bug, user-reported).
        // Establishing the membership in THIS transaction keeps create atomic (fail-closed: if the
        // membership insert fails, the whole create rolls back). Root / no-company creators are left
        // unassigned exactly as before (root has no single company and sees everyone anyway).
        establishCreatorCompanyMembership(saved);

        return UserDto.from(saved);
    }

    /**
     * Grants the newly-created {@code user} an explicit {@link UserCompany} membership in the
     * creator's active company, in the same transaction as the user save.
     *
     * <p>No-op when the caller is root or has no active company context (ADR-0046: root has no
     * single company). Idempotent — skips if an active membership already exists (defensive; a
     * brand-new user never does). The audited grant mirrors {@link UserCompanyServiceImpl#assign}
     * so the membership shows up in the append-only trail like any explicit assignment.
     */
    private void establishCreatorCompanyMembership(AppUser user) {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.root()) {
            return; // root / no principal → leave unassigned (root sees all; no single company)
        }
        Long companyId = principal.companyId();
        if (companyId == null) {
            return; // no active company scope → nothing to assign
        }
        if (userCompanies.existsByUserIdAndCompanyIdAndRevokedAtIsNull(user.getId(), companyId)) {
            return; // already a member — idempotent, no duplicate row
        }
        // findScopedById (not findById): companyId is the caller's OWN JWT scope, not request
        // input — a self-scope lookup, exempt from the confused-deputy guard by name.
        Company company = companies.findScopedById(companyId).orElse(null);
        if (company == null) {
            return; // active company vanished (shouldn't happen) → fail-open on membership only
        }
        UserCompany membership = new UserCompany(user.getId(), company, principal.userId());
        UserCompany savedMembership = userCompanies.save(membership);

        audit.record(AuditEvent.of(AuditActions.USER_COMPANY_ASSIGN, "user_company",
                        savedMembership.getId(), savedMembership.getUid())
                .detail(Map.of("userUid", user.getUid(), "companyUid", company.getUid())));
    }

    @Override
    @Transactional(readOnly = true)
    public UserDto getByUid(String uid) {
        AppUser user = requireByUid(uid);
        RequestContext.Principal principal = RequestContext.get();
        // Root always resolves; non-root callers are subject to two guards:
        //   1. Root user → 404 (don't reveal existence — security hardening 2026-06-25).
        //   2. Out-of-company → 404 (tenant-isolation fix, security audit 2026-06-25).
        if (principal == null || !principal.root()) {
            if (user.isRoot()) {
                throw NotFoundException.of("User", uid);
            }
            Long companyId = (principal != null) ? principal.companyId() : null;
            if (companyId == null || !users.existsUserInCompany(user.getId(), companyId)) {
                throw NotFoundException.of("User", uid);
            }
        }
        return UserDto.from(user);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> list() {
        // Root sees everyone org-wide (including other root users).
        // Non-root (and null principal → no company context) sees only non-root users in their
        // company; null company → fail-closed empty list.
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && principal.root()) {
            return users.findAllByOrderByUsername().stream().map(UserDto::from).toList();
        }
        Long companyId = (principal != null) ? principal.companyId() : null;
        if (companyId == null) {
            return List.of();
        }
        return users.findNonRootInCompanyOrderByUsername(companyId).stream()
                .map(UserDto::from).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserDto> listOrgWide() {
        // Root sees the full org list; non-root callers see org-wide but without root users.
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && principal.root()) {
            return users.findAllByOrderByUsername().stream().map(UserDto::from).toList();
        }
        return users.findByRootFalseOrderByUsername().stream().map(UserDto::from).toList();
    }

    @Override
    public UserDto updateByUid(String uid, UpdateUserRequest request) {
        AppUser user = requireInScope(uid);
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        user.setPhone(request.phone());

        // D-6: fact-only — no old/new field values in the detail (minimise PII in the trail).
        audit.record(AuditEvent.of(AuditActions.USER_UPDATE, "app_users", user.getId(), user.getUid()));

        return UserDto.from(user); // dirty-checked within the TX
    }

    @Override
    public void disableByUid(String uid) {
        AppUser user = requireInScope(uid);
        if (user.isRoot()) {
            throw new ConflictException("A root administrator cannot be disabled via the API.");
        }
        MasterStatus previous = user.getStatus();
        user.setStatus(MasterStatus.INACTIVE);

        audit.record(AuditEvent.of(AuditActions.USER_DISABLE, "app_users", user.getId(), user.getUid())
                .detail(Map.of("previousStatus", previous.name(), "newStatus", MasterStatus.INACTIVE.name())));
    }

    @Override
    public void enableByUid(String uid) {
        AppUser user = requireInScope(uid);
        MasterStatus previous = user.getStatus();
        user.setStatus(MasterStatus.ACTIVE);

        audit.record(AuditEvent.of(AuditActions.USER_ENABLE, "app_users", user.getId(), user.getUid())
                .detail(Map.of("previousStatus", previous.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    @Override
    public void unlockByUid(String uid) {
        AppUser user = requireInScope(uid);
        user.unlock();

        audit.record(AuditEvent.of(AuditActions.USER_UNLOCK, "app_users", user.getId(), user.getUid()));
    }

    @Override
    public void setPasswordByUid(String uid, SetPasswordRequest request) {
        AppUser user = requireInScope(uid);

        // ADR-0059 authority-containment: you may not reset the password of a user who OUTRANKS you.
        // Resolve the target's effective permissions across the caller's active company (branch-
        // agnostic — a takeover lands in the victim's default branch) and require them to be a subset
        // of the caller's. Closes the same-company peer/admin account-takeover path. Root is exempt;
        // requireInScope already 404s a root target for a non-root caller. Applied to setPassword ONLY
        // — update/disable/enable/unlock confer no authority.
        RequestContext.Principal principal = RequestContext.get();
        if (principal != null && !principal.root()) {
            Long companyId = principal.companyId();
            List<String> targetCodes = (companyId == null)
                    ? List.of()
                    : userRoles.resolvePermissionCodesAnyBranch(user.getId(), companyId);
            authorityCeiling.assertCanConfer(principal, targetCodes);
        }

        passwordPolicy.validate(request.password());
        user.changePassword(passwordEncoder.encode(request.password()), Instant.now());

        // D-6: NEVER log the password or its hash — empty detail.
        audit.record(AuditEvent.of(AuditActions.USER_PASSWORD_SET, "app_users", user.getId(), user.getUid()));
    }

    private AppUser requireByUid(String uid) {
        return Lookups.orNotFound(users.findByUid(uid), "User", uid);
    }

    /**
     * Loads the user by uid and enforces tenant-isolation for non-root callers.
     *
     * <p>Mirrors the check in {@link #getByUid}: root users short-circuit to allowed; non-root
     * principals get {@code 404} (not {@code 403}) when the target is a root user OR the target
     * does not belong to the principal's active company — avoiding existence leakage identical to
     * the read path (security audit 2026-06-26).
     *
     * <p>This is intentionally 404, not 403, so a cross-tenant attacker cannot distinguish
     * "user exists in another company" from "user does not exist at all".
     */
    private AppUser requireInScope(String uid) {
        AppUser user = requireByUid(uid);
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || !principal.root()) {
            if (user.isRoot()) {
                throw NotFoundException.of("User", uid);
            }
            Long companyId = (principal != null) ? principal.companyId() : null;
            if (companyId == null || !users.existsUserInCompany(user.getId(), companyId)) {
                throw NotFoundException.of("User", uid);
            }
        }
        return user;
    }
}
