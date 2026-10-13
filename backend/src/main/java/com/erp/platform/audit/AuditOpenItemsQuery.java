package com.erp.platform.audit;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read port for "open item" audit rows — a fact recorded by one action and closed later by a
 * second, APPENDED action on the same target uid (never by editing the first row). The GL posting
 * exceptions of ACC-02 are the first user: {@code GL.POSTING.FAILED} opens an item,
 * {@code GL.POSTING.RESOLVED} closes it.
 *
 * <p>Lives in {@code com.erp.platform.audit} because {@link AuditRepository} may not be used
 * anywhere else (append-only guard, ModuleBoundaryTest). Exposes reads and a transaction-scoped
 * lock only — there is no write path here; writes still go through {@link AuditService}.
 */
@Component
public class AuditOpenItemsQuery {

    private final AuditRepository audit;

    public AuditOpenItemsQuery(AuditRepository audit) {
        this.audit = audit;
    }

    /** Filtered page of one company's items; blank filters are ignored. */
    @Transactional(readOnly = true)
    public Page<AuditLog> findOpenItems(Long companyId, String targetType, String openAction,
                                        String closeAction, String sourceType, String fromDate,
                                        String toDate, boolean includeClosed, Pageable pageable) {
        return audit.findOpenItems(companyId, targetType, openAction, closeAction,
                blank(sourceType), blank(fromDate), blank(toDate), includeClosed, pageable);
    }

    /** Whether an open item with the same kind/sourceType/sourceRef detail keys exists. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean existsOpenItem(Long companyId, String targetType, String openAction,
                                  String closeAction, String kind, String sourceType,
                                  String sourceRef) {
        return audit.existsOpenItem(companyId, targetType, openAction, closeAction,
                blank(kind), blank(sourceType), blank(sourceRef));
    }

    /** Rows of one company for the given targets and action. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<AuditLog> findByTargets(Long companyId, String targetType, String action,
                                        Collection<String> targetUids) {
        if (targetUids == null || targetUids.isEmpty()) {
            return List.of();
        }
        return audit.findByCompanyIdAndTargetTypeAndActionAndTargetUidIn(
                companyId, targetType, action, targetUids);
    }

    /**
     * Takes a transaction-scoped advisory lock on one item. MANDATORY: the lock lives exactly as
     * long as the caller's read-write transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockItem(String targetType, String targetUid) {
        audit.lockItem(targetType + ":" + targetUid);
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }
}
