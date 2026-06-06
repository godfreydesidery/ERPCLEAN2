package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.PartyCodeSequence;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface PartyCodeSequenceRepository extends JpaRepository<PartyCodeSequence, Long> {

    /**
     * Locks the row for the given company+kind so the caller can safely increment {@code next_value}
     * inside the same transaction (ADR-0006 D-7). Two creates for the same company+kind block, not
     * race; different company/kind rows do not contend.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PartyCodeSequence s WHERE s.companyId = :companyId AND s.partyKind = :kind")
    Optional<PartyCodeSequence> findByCompanyIdAndPartyKindForUpdate(
            @Param("companyId") Long companyId,
            @Param("kind") String kind);
}
