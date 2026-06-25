package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UpdateUserRequest;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
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
 */
@Service
@Transactional
public class UserServiceImpl implements UserService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;

    public UserServiceImpl(AppUserRepository users,
                           PasswordEncoder passwordEncoder,
                           PasswordPolicy passwordPolicy,
                           AuditService audit) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
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
        AppUser saved = users.save(user);

        audit.record(AuditEvent.of(AuditActions.USER_CREATE, "app_users", saved.getId(), saved.getUid())
                .detail(Map.of("username", saved.getUsername(), "displayName", saved.getDisplayName())));

        return UserDto.from(saved);
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
        AppUser user = requireByUid(uid);
        user.setDisplayName(request.displayName());
        user.setEmail(request.email());
        user.setPhone(request.phone());

        // D-6: fact-only — no old/new field values in the detail (minimise PII in the trail).
        audit.record(AuditEvent.of(AuditActions.USER_UPDATE, "app_users", user.getId(), user.getUid()));

        return UserDto.from(user); // dirty-checked within the TX
    }

    @Override
    public void disableByUid(String uid) {
        AppUser user = requireByUid(uid);
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
        AppUser user = requireByUid(uid);
        MasterStatus previous = user.getStatus();
        user.setStatus(MasterStatus.ACTIVE);

        audit.record(AuditEvent.of(AuditActions.USER_ENABLE, "app_users", user.getId(), user.getUid())
                .detail(Map.of("previousStatus", previous.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    @Override
    public void unlockByUid(String uid) {
        AppUser user = requireByUid(uid);
        user.unlock();

        audit.record(AuditEvent.of(AuditActions.USER_UNLOCK, "app_users", user.getId(), user.getUid()));
    }

    @Override
    public void setPasswordByUid(String uid, SetPasswordRequest request) {
        AppUser user = requireByUid(uid);
        passwordPolicy.validate(request.password());
        user.changePassword(passwordEncoder.encode(request.password()), Instant.now());

        // D-6: NEVER log the password or its hash — empty detail.
        audit.record(AuditEvent.of(AuditActions.USER_PASSWORD_SET, "app_users", user.getId(), user.getUid()));
    }

    private AppUser requireByUid(String uid) {
        return Lookups.orNotFound(users.findByUid(uid), "User", uid);
    }
}
