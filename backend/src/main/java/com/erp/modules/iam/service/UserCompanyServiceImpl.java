package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserCompany;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
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
 * Authoritative user↔company membership (ADR-0046, supersedes the non-authoritative phase of
 * ADR-0045). Membership is a write-path prerequisite: {@link #isActiveMember} gates role grants and
 * branch assignments (assign-company-first), and {@link #remove} refuses while access remains.
 * Runtime access decisions still live in {@link com.erp.platform.security.PermissionResolver}
 * (role→permission); the read/isolation oracle stays additive as a defensive superset.
 *
 * <p>{@link #ensureMembership} remains only for the startup reconcile that back-fills memberships
 * for pre-existing grants/branches (it is no longer called from grant/assign).
 */
@Service
@Transactional
public class UserCompanyServiceImpl implements UserCompanyService {

    private static final Logger log = LoggerFactory.getLogger(UserCompanyServiceImpl.class);

    private final UserCompanyRepository userCompanies;
    private final AppUserRepository     users;
    private final CompanyRepository     companies;
    private final UserRoleRepository    userRoles;
    private final UserBranchRepository  userBranches;
    private final ScopeGuard            scopeGuard;
    private final AuditService          audit;

    public UserCompanyServiceImpl(UserCompanyRepository userCompanies,
                                  AppUserRepository     users,
                                  CompanyRepository     companies,
                                  UserRoleRepository    userRoles,
                                  UserBranchRepository  userBranches,
                                  ScopeGuard            scopeGuard,
                                  AuditService          audit) {
        this.userCompanies = userCompanies;
        this.users         = users;
        this.companies     = companies;
        this.userRoles     = userRoles;
        this.userBranches  = userBranches;
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

    @Override
    @Transactional(readOnly = true)
    public boolean isActiveMember(Long userId, Long companyId) {
        if (userId == null || companyId == null) {
            return false;
        }
        return userCompanies.existsByUserIdAndCompanyIdAndRevokedAtIsNull(userId, companyId);
    }

    // -------------------------------------------------------------------------
    // Explicit assign / remove
    // -------------------------------------------------------------------------

    @Override
    public UserCompanyDto assign(AssignUserCompanyRequest request) {
        // P3-3 (ADR-0062, G1): asserted before any membership lookup — see
        // UserServiceImpl.requireInScope for why the ordering matters.
        assertSameTenantAsCaller(request.userUid());
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
            throw new ConflictException("This membership has already been revoked.");
        }

        // ADR-0046: authoritative membership — refuse to remove while the user still holds access in
        // this company. Roles/branches must be cleared first (no silent cascade).
        Long userId    = uc.getUserId();
        Long companyId = uc.getCompany().getId();
        if (userRoles.existsByUserIdAndCompanyIdAndRevokedAtIsNull(userId, companyId)
                || userBranches.existsByUserIdAndBranchCompanyId(userId, companyId)) {
            throw new ConflictException(
                    "Remove this user's roles and branch assignments in "
                            + uc.getCompany().getName() + " before removing the company membership.");
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

    /**
     * P3-3: refuses when the target user belongs to another tenant. NOT FOUND, not FORBIDDEN: a
     * distinct refusal confirms the uid names a real user in some other organisation.
     */
    private void assertSameTenantAsCaller(String userUid) {
        com.erp.platform.security.RequestContext.Principal principal =
                com.erp.platform.security.RequestContext.get();
        if (principal == null || principal.organisationId() == null || userUid == null) {
            return;
        }
        users.findByUid(userUid)
                .filter(u -> u.getOrganisationId() != null
                        && !principal.organisationId().equals(u.getOrganisationId()))
                .ifPresent(u -> {
                    throw com.erp.platform.common.api.NotFoundException.of("User", userUid);
                });
    }
}
