package com.erp.platform.audit;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the audit trail (ADR-0004 D-7): filtered, paged search with internal ids resolved to
 * uids/usernames for the wire. Separate from the write-only {@link AuditService} so the append-only
 * port stays narrow. Reading IAM repositories from the platform spine mirrors the established
 * pattern (PermissionResolver/ScopeGuard) — not a module-boundary breach.
 */
@Service
@Transactional(readOnly = true)
public class AuditReadService {

    private final AuditRepository audit;
    private final AppUserRepository users;
    private final CompanyRepository companies;
    private final BranchRepository branches;

    public AuditReadService(AuditRepository audit, AppUserRepository users,
                            CompanyRepository companies, BranchRepository branches) {
        this.audit = audit;
        this.users = users;
        this.companies = companies;
        this.branches = branches;
    }

    /** Resolve an actor uid to its id for filtering; null when not supplied or unknown. */
    public Long resolveActorId(String actorUid) {
        if (actorUid == null || actorUid.isBlank()) {
            return null;
        }
        return users.findByUid(actorUid).map(u -> u.getId()).orElse(-1L); // -1 => match nothing
    }

    public Page<AuditLogDto> search(Long actorUserId, String action, String targetType,
                                    String targetUid, Instant from, Instant to, Pageable pageable) {
        // Tenant scope (F10, ADR-0004 D-7 follow-up): root reads org-wide; a non-root AUDIT.VIEW
        // holder is confined to their ACTIVE COMPANY's rows — mirroring ScopeGuard's same-company
        // rule everywhere else. Fail closed: a non-root caller with no active company matches nothing
        // (companyId == null => company_id = NULL predicate, which no row satisfies), never org-wide.
        RequestContext.Principal caller = RequestContext.get();
        boolean isRoot = caller != null && caller.root();
        Long scopeCompanyId = caller != null ? caller.companyId() : null;

        // Compose one Specification per active filter (null/blank dropped — avoids the
        // (:p IS NULL OR col = :p) null-bind that Postgres can't type, SQLState 42P18).
        Specification<AuditLog> spec = isRoot ? null : eq("companyId", scopeCompanyId);
        spec = and(spec, actorUserId == null ? null : eq("actorUserId", actorUserId));
        spec = and(spec, blankToNull(action) == null ? null : eq("action", action));
        spec = and(spec, blankToNull(targetType) == null ? null : eq("targetType", targetType));
        spec = and(spec, blankToNull(targetUid) == null ? null : eq("targetUid", targetUid));
        spec = and(spec, from == null ? null : (r, q, cb) -> cb.greaterThanOrEqualTo(r.get("at"), from));
        spec = and(spec, to == null ? null : (r, q, cb) -> cb.lessThanOrEqualTo(r.get("at"), to));

        return audit.findAll(spec, pageable).map(this::toDto);
    }

    /** An equality Specification on one attribute; {@code value} may be null (renders {@code = NULL}). */
    private static Specification<AuditLog> eq(String attribute, Object value) {
        return (root, query, cb) -> cb.equal(root.get(attribute), value);
    }

    /** Combine two Specifications with AND, tolerating nulls (a null side contributes nothing). */
    private static Specification<AuditLog> and(Specification<AuditLog> a, Specification<AuditLog> b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a.and(b);
    }

    private AuditLogDto toDto(AuditLog a) {
        String actorUid = null;
        String actorUsername = null;
        if (a.getActorUserId() != null) {
            var u = users.findById(a.getActorUserId()).orElse(null);
            if (u != null) {
                actorUid = u.getUid();
                actorUsername = u.getUsername();
            }
        }
        String companyUid = a.getCompanyId() != null
                ? companies.findById(a.getCompanyId()).map(c -> c.getUid()).orElse(null)
                : null;
        String branchUid = a.getBranchId() != null
                ? branches.findById(a.getBranchId()).map(b -> b.getUid()).orElse(null)
                : null;
        return new AuditLogDto(
                actorUid,
                actorUsername,
                a.getAction(),
                a.getTargetType(),
                a.getTargetUid(),
                companyUid,
                branchUid,
                a.getDetail(),
                a.getAt() != null ? a.getAt().toString() : null,
                a.getIp());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
