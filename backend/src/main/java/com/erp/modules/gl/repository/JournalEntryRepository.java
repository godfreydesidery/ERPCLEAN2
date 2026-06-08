package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long> {

    Optional<JournalEntry> findByUid(String uid);

    Optional<JournalEntry> findByCompanyIdAndUid(Long companyId, String uid);

    Page<JournalEntry> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Looks up a SALES entry by source_ref (invoice uid) — used by SaleVoidingHandler to find the
     * original sales journal (ADR-0013 D-6). Hits ix_journal_entries_source.
     */
    Optional<JournalEntry> findByCompanyIdAndSourceTypeAndSourceRef(
            Long companyId, JournalSourceType sourceType, String sourceRef);

    /** Single-column projection for ScopeGuard case "journalentry" (ADR-0013 D-10). */
    @Query("SELECT e.companyId FROM JournalEntry e WHERE e.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Check whether a reversing entry already exists for a given original (BR-GL-11). */
    boolean existsByReversalOfId(Long originalEntryId);
}
