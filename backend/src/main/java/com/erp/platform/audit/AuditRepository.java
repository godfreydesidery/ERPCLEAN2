package com.erp.platform.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/**
 * Persistence port for {@link AuditLog}. Append-only: callers use only inserts (via
 * {@link AuditService}) and reads — no {@code delete}/bulk-update is invoked anywhere (enforced by
 * an ArchUnit rule, ADR-0004 D-5; this interface is not referenced outside {@code com.erp.platform.audit}).
 *
 * <p>Filtered paged search uses {@link JpaSpecificationExecutor}: the predicates are built
 * dynamically in {@code AuditReadService}, adding a clause only for a non-null filter. This avoids
 * the {@code (:p IS NULL OR col = :p)} JPQL form, which leaves Postgres unable to infer the bind
 * type of a null parameter (SQLState 42P18).
 */
public interface AuditRepository
        extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
