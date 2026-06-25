package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserCompany;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Non-authoritative user↔company membership (V77). The additive oracle means this service only
 * writes membership rows — it never reads them for an access decision at runtime. Access decisions
 * remain in {@link com.erp.platform.security.PermissionResolver} (role→permission path).
 *
 * <p>{@link #ensureMembership} is the key hook: it is called by {@link UserRoleServiceImpl} and
 * {@link UserBranchServiceImpl} after a grant/assign persists so that a membership row always
 * exists whenever a user gains access to a company via either path. It is intentionally
 * exception-free — the auto-create must never fail a grant.
 */
@Service
@Transactional
public class UserCompanyServiceImpl implements UserCompanyService {

    private static final Logger log = LoggerFactory.getLogger(UserCompanyServiceImpl.class);

    private final UserCompanyRepository userCompanies;
    private final AppUserRepository     users;
    private final CompanyRepository     companies;
    private final ScopeGuard            scopeGuard;
    private final AuditService          audit;

    public UserCompanyServiceImpl(UserCompanyRepository userCompanies,
                                  AppUserRepository     users,
                                  CompanyRepository     companies,
                                  ScopeGuard            scopeGuard,
                                  AuditService          audit) {
        this.userCompanies = userCompanies;
        this.users         = users;
        this.companies     = companies;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
    }

    // -------------------------------------------------------------------------
    // Core hook — idempotent, never throws
    // -------------------------------------------------------------------------

    /**
     * Ensure an active membership row exists for (userId, companyId). Runs in its
     * <em>own</em> transaction ({@code REQUIRES_NEW}) so that a failure — most commonly a
     * concurrent-insert race on {@code uq_user_company_active} producing a
     * {@code DataIntegrityViolationException} — rolls back only this inner TX, leaving the
     * caller's grant/branch-assign TX untouched. The try/catch absorbs the rollback so the
     * caller never sees an exception.
     *
     * <p>Because {@code REQUIRES_NEW} commits independently, the membership row may persist even
     * if the outer grant later rolls back (e.g. the grant fails a downstream validation after
     * this call). That is acceptable: the non-authoritative oracle already supports the
     * "assigned-to-company but not yet granted a role" state, and a dangling membership row is
     * harmless — it only adds, never blocks.
     *
     * <p>Call sites must invoke this via the injected {@link UserCompanyService} interface
     * (proxied by Spring), never as a self-call within this bean — {@code REQUIRES_NEW}
     * requires the proxy to intercept. Both current call sites
     * ({@link UserRoleServiceImpl} and {@link UserBranchServiceImpl}) satisfy this requirement.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void ensureMembership(Long userId, Long companyId, Long assignedBy) {
        try {
            if (userCompanies.existsByUserIdAndCompanyIdAndRevokedAtIsNull(userId, companyId)) {
                return; // already active — no-op
            }
            Company company = companies.findById(companyId).orElse(null);
            if (company == null) {
                log.warn("ensureMembership: company {} not found, skipping user_company insert", companyId);
                return;
            }
            UserCompany uc = new UserCompany(userId, company, assignedBy);
            userCompanies.save(uc);
        } catch (Exception ex) {
            // Concurrent-insert race (DataIntegrityViolationException on uq_user_company_active)
            // or any other insert failure: the inner TX is already rolled back by the time we
            // reach here. Log and return — the winner's row exists (race) or the row is simply
            // not created (other failure). Either way the outer grant TX is unaffected.
            log.error("ensureMembership failed for user={} company={} — skipping: {}",
                    userId, companyId, ex.getMessage(), ex);
        }
    }

    // -------------------------------------------------------------------------
    // Explicit assign / remove
    // -------------------------------------------------------------------------

    @Override
    public UserCompanyDto assign(AssignUserCompanyRequest request) {
        AppUser user    = Lookups.orNotFound(users.findByUid(request.userUid()), "User", request.userUid());
        Company company = Lookups.orNotFound(companies.findByUid(request.companyUid()), "Company", request.companyUid());

        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        if (userCompanies.existsByUserIdAndCompanyIdAndRevokedAtIsNull(user.getId(), company.getId())) {
            throw new ConflictException("User already has an active membership in that company.");
        }

        Long actorId = actorId();
        UserCompany uc = new UserCompany(user.getId(), company, actorId);
        UserCompany saved = userCompanies.save(uc);

        audit.record(AuditEvent.of(AuditActions.USER_COMPANY_ASSIGN, "user_company",
                        saved.getId(), saved.getUid())
                .detail(Map.of("userUid", user.getUid(), "companyUid", company.getUid())));

        return UserCompanyDto.from(saved, user.getUid());
    }

    @Override
    public void remove(String userCompanyUid) {
        UserCompany uc = Lookups.orNotFound(userCompanies.findByUid(userCompanyUid),
                "Company membership", userCompanyUid);

        scopeGuard.assertCanActIn(RequestContext.get(), uc.getCompany().getId());

        if (!uc.isActive()) {
            throw new ConflictException("Membership already revoked: " + userCompanyUid);
        }

        String userUid    = users.findById(uc.getUserId()).map(AppUser::getUid).orElse(null);
        String companyUid = uc.getCompany().getUid();

        uc.revoke(Instant.now());

        audit.record(AuditEvent.of(AuditActions.USER_COMPANY_REMOVE, "user_company",
                        uc.getId(), uc.getUid())
                .detail(Map.of("userUid",    userUid    != null ? userUid    : "",
                               "companyUid", companyUid != null ? companyUid : "")));
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<UserCompanyDto> listForUser(String userUid) {
        AppUser user = Lookups.orNotFound(users.findByUid(userUid), "User", userUid);

        // Tenant-isolation: root sees all memberships; non-root sees only the membership that
        // matches their active company (consistent with UserBranchServiceImpl.listForUser and
        // UserRoleServiceImpl.listForUser — fail-closed on null active company).
        RequestContext.Principal principal = RequestContext.get();
        List<UserCompany> rows;
        if (principal != null && !principal.root()) {
            Long activeCompany = principal.companyId();
            if (activeCompany == null) {
                return List.of();
            }
            rows = userCompanies.findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(user.getId())
                    .stream()
                    .filter(uc -> activeCompany.equals(uc.getCompany().getId()))
                    .toList();
        } else {
            rows = userCompanies.findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(user.getId());
        }

        return rows.stream()
                .map(uc -> UserCompanyDto.from(uc, user.getUid()))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
