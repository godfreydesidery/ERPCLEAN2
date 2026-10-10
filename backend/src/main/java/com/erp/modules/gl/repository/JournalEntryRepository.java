package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>,
        org.springframework.data.jpa.repository.JpaSpecificationExecutor<JournalEntry> {

    /** Entries of the given source documents (company-scoped) — document-ref lookup (ACC-19). */
    List<JournalEntry> findByCompanyIdAndSourceRefIn(Long companyId,
                                                     java.util.Collection<String> sourceRefs);

    Optional<JournalEntry> findByUid(String uid);

    Optional<JournalEntry> findByCompanyIdAndUid(Long companyId, String uid);

    Page<JournalEntry> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Looks up a SALES entry by source_ref (invoice uid) — used by SaleVoidingHandler to find the
     * original sales journal (ADR-0013 D-6). Hits ix_journal_entries_source.
     */
    Optional<JournalEntry> findByCompanyIdAndSourceTypeAndSourceRef(
            Long companyId, JournalSourceType sourceType, String sourceRef);

    /**
     * Uids of a source document's entries that are still live — not reversed and not themselves a
     * reversal — oldest first. Hits ix_journal_entries_source.
     */
    @Query("""
            SELECT e.uid FROM JournalEntry e
            WHERE e.companyId = :companyId
              AND e.sourceType = :sourceType
              AND e.sourceRef = :sourceRef
              AND e.reversed = false
              AND e.reversalOfId IS NULL
            ORDER BY e.id
            """)
    List<String> findLiveUidsBySource(@Param("companyId") Long companyId,
                                      @Param("sourceType") JournalSourceType sourceType,
                                      @Param("sourceRef") String sourceRef);

    /** Single-column projection for ScopeGuard case "journalentry" (ADR-0013 D-10). */
    @Query("SELECT e.companyId FROM JournalEntry e WHERE e.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Check whether a reversing entry already exists for a given original (BR-GL-11). */
    boolean existsByReversalOfId(Long originalEntryId);

    /**
     * True when the company has at least one journal entry — used by the base-currency-change
     * guard (ADR-0039 D-9 / OQ-CCY-08): once any GL posting exists, the base is immutable.
     */
    boolean existsByCompanyId(Long companyId);

    /**
     * Number of entries a source document has under one source type — the GL posting-exception
     * idempotency probe (ACC-02): a re-post is refused once this count has grown past the count
     * recorded when the automatic posting failed.
     */
    long countByCompanyIdAndSourceTypeAndSourceRef(Long companyId, JournalSourceType sourceType,
                                                   String sourceRef);

    /** Newest entry of a source document under one source type (company-scoped). */
    Optional<JournalEntry> findFirstByCompanyIdAndSourceTypeAndSourceRefOrderByIdDesc(
            Long companyId, JournalSourceType sourceType, String sourceRef);
}
