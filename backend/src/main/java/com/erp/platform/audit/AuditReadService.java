package com.erp.platform.audit;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
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
        // Build the predicate dynamically — add a clause ONLY for a supplied filter. This avoids the
        // (:p IS NULL OR col = :p) form whose null bind Postgres can't type (SQLState 42P18).
        Specification<AuditLog> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (actorUserId != null) {
                predicates.add(cb.equal(root.get("actorUserId"), actorUserId));
            }
            if (blankToNull(action) != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (blankToNull(targetType) != null) {
                predicates.add(cb.equal(root.get("targetType"), targetType));
            }
            if (blankToNull(targetUid) != null) {
                predicates.add(cb.equal(root.get("targetUid"), targetUid));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("at"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("at"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return audit.findAll(spec, pageable).map(this::toDto);
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
