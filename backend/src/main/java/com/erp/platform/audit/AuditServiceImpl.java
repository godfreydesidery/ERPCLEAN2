package com.erp.platform.audit;

import com.erp.platform.security.RequestContext;
import com.erp.platform.security.RequestContext.Principal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Concrete {@link AuditService}. Both overloads use {@code Propagation.MANDATORY} — if there is
 * no active transaction the call fails fast rather than silently skipping the audit row.
 *
 * <p>Jackson serialises the {@code detail} map to a canonical JSON string before storage; the
 * JSONB column stores it as Postgres native JSONB (queryable, indexed if needed later).
 */
@Service
class AuditServiceImpl implements AuditService {

    private final AuditRepository auditRepository;
    private final ObjectMapper    objectMapper;

    AuditServiceImpl(AuditRepository auditRepository, ObjectMapper objectMapper) {
        this.auditRepository = auditRepository;
        this.objectMapper    = objectMapper;
    }

    /**
     * Authenticated path: actor/company/branch sourced from {@link RequestContext}.
     * IP is null until {@code RequestContext.Principal} gains the {@code ip} field (ADR-0004 D-4,
     * implemented in a later step).
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event) {
        Principal ctx = RequestContext.get();

        Long actorUserId = null;
        Long companyId   = null;
        Long branchId    = null;
        Long organisationId = null;

        String ip = null;
        if (ctx != null) {
            actorUserId = ctx.userId();
            companyId   = ctx.companyId();
            branchId    = ctx.branchId();
            organisationId = ctx.organisationId();
            ip          = ctx.ip();
        }

        persist(event, actorUserId, companyId, branchId, ip, organisationId);
    }

    /**
     * Unauthenticated path (login/lockout): explicit actor + IP; company/branch are null because
     * there is no authenticated scope at this point (ADR-0004 D-3). The organisation is null for the
     * same reason — there is no established tenant yet, and guessing one would write a fabricated
     * attribution into an append-only table.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEvent event, Long actorUserId, String ip) {
        persist(event, actorUserId, null, null, ip, null);
    }

    /**
     * Independent (REQUIRES_NEW) record — commits in its own transaction regardless of the caller's.
     * For security-bypass audits raised from read-only query paths (ISSUES-REGISTER #11): a
     * MANDATORY INSERT would fail inside a {@code @Transactional(readOnly = true)} caller. Same
     * RequestContext-sourced actor/company/branch as {@link #record(AuditEvent)}.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependent(AuditEvent event) {
        Principal ctx = RequestContext.get();
        Long actorUserId = ctx != null ? ctx.userId() : null;
        Long companyId   = ctx != null ? ctx.companyId() : null;
        Long branchId    = ctx != null ? ctx.branchId() : null;
        Long organisationId = ctx != null ? ctx.organisationId() : null;
        String ip        = ctx != null ? ctx.ip() : null;
        persist(event, actorUserId, companyId, branchId, ip, organisationId);
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private void persist(AuditEvent event,
                         Long actorUserId,
                         Long companyId,
                         Long branchId,
                         String ip,
                         Long organisationId) {
        String detailJson = serializeDetail(event.detail());
        AuditLog log = new AuditLog(
                actorUserId,
                event.action(),
                event.targetType(),
                event.targetId(),
                event.targetUid(),
                companyId,
                branchId,
                detailJson,
                Instant.now(),
                ip,
                organisationId);
        auditRepository.save(log);
    }

    /** Null-safe: a null or empty map serialises to null (no JSONB column entry). */
    private String serializeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(detail);
        } catch (JsonProcessingException e) {
            // Should never happen for a plain String-keyed map; propagate as runtime to
            // fail the calling transaction (better than a silent empty detail).
            throw new IllegalStateException("Failed to serialise audit detail to JSON", e);
        }
    }
}
