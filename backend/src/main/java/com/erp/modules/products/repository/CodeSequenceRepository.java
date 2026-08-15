package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.CodeSequence;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CodeSequenceRepository extends JpaRepository<CodeSequence, Long> {

    /**
     * Locks the row for the given company+entityKind so the caller can safely increment
     * {@code next_value} inside the same transaction (ADR-0007 D-6). Two creates for the same
     * company+kind block, not race; different company/kind rows do not contend.
     */
    /**
     * Plain existence check for {@code CodeSequenceSeeder} (ADR-0062 P5-6) — deliberately NOT the
     * locking finder below. Seeding only asks "is this row already here"; taking a
     * PESSIMISTIC_WRITE for that would make provisioning contend with live number allocation for
     * no reason.
     */
    boolean existsByCompanyIdAndEntityKind(Long companyId, String entityKind);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM CodeSequence s WHERE s.companyId = :companyId AND s.entityKind = :entityKind")
    Optional<CodeSequence> findByCompanyIdAndEntityKindForUpdate(
            @Param("companyId") Long companyId,
            @Param("entityKind") String entityKind);
}
