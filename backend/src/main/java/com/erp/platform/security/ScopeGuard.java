package com.erp.platform.security;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ForbiddenException;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The single home for tenant-scope enforcement (ADR-0002, building on ADR-0001 D-A). Answers one
 * question: may the caller act on a target that lives in company X? Yes iff the caller is root, or
 * the caller's active company equals X. Used by {@link PermissionChecks} for path-uid ops and
 * directly by services for body-scoped ops (grant/revoke, branch create), so the rule lives exactly
 * once and a forgotten call fails closed (returns/throws "not permitted").
 *
 * <p>Reading the Company/Branch repositories from the security layer mirrors the established
 * {@link PermissionResolver} pattern (which reads {@code UserRoleRepository}); this is the
 * cross-cutting spine, not a peer module (ArchUnit note in ADR-0002).
 */
@Component
public class ScopeGuard {

    private final CompanyRepository companies;
    private final BranchRepository branches;
    private final AuditService audit;

    public ScopeGuard(CompanyRepository companies, BranchRepository branches, AuditService audit) {
        this.companies = companies;
        this.branches = branches;
        this.audit = audit;
    }

    /** Resolve a target uid to its owning company id, per target type (ADR-0002 §2). */
    public Optional<Long> companyIdOf(String targetType, String uid) {
        if (targetType == null || uid == null) {
            return Optional.empty();
        }
        return switch (targetType.toLowerCase()) {
            case "company" -> companies.findByUid(uid).map(c -> c.getId());
            case "branch" -> branches.findByUid(uid).map(b -> b.getCompany().getId());
            // organisation is global (root-only, not company-scoped); unknown types resolve to empty.
            default -> Optional.empty();
        };
    }

    /** True if the caller may act in {@code companyId}: root, or it is their active company. */
    public boolean canActIn(RequestContext.Principal principal, Long companyId) {
        if (principal == null || companyId == null) {
            return false;
        }
        return principal.root() || companyId.equals(principal.companyId());
    }

    /**
     * Whether the caller may act on the given target uid (root, or same active company). An
     * unresolvable target denies — never "allow because unknown".
     */
    public boolean canActOn(RequestContext.Principal principal, String targetType, String uid) {
        if (principal != null && principal.root()) {
            return true;
        }
        return companyIdOf(targetType, uid)
                .map(companyId -> canActIn(principal, companyId))
                .orElse(false);
    }

    /** Imperative form for the service layer (body-scoped ops): throw 403 if the caller can't act. */
    public void assertCanActIn(RequestContext.Principal principal, Long companyId) {
        if (!canActIn(principal, companyId)) {
            throw ForbiddenException.notPermitted();
        }
        // Audit the security-interesting bypass: root acting OUTSIDE its active company (a non-root
        // caller would have been denied here). Root acting within its own company is ordinary and is
        // already captured by the action's own audit row (ADR-0004 D-9). Called from @Transactional
        // service methods, so the MANDATORY audit write has a transaction to join.
        if (principal != null && principal.root() && companyId != null
                && !companyId.equals(principal.companyId())) {
            audit.record(AuditEvent.of(AuditActions.ROOT_BYPASS, "companies", companyId, null)
                    .detail(Map.of("activeCompanyId", String.valueOf(principal.companyId()),
                            "targetCompanyId", String.valueOf(companyId))));
        }
    }
}
