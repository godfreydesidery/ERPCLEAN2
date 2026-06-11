package com.erp.modules.documents.repository;

import com.erp.modules.documents.domain.entity.GeneratedDocument;
import com.erp.modules.documents.domain.enums.DocumentType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GeneratedDocumentRepository extends JpaRepository<GeneratedDocument, Long> {

    Optional<GeneratedDocument> findByUid(String uid);

    /** ScopeGuard support (ADR-0023 D-9). */
    @Query("SELECT g.companyId FROM GeneratedDocument g WHERE g.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<GeneratedDocument> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT g FROM GeneratedDocument g
            WHERE g.companyId = :companyId
              AND (:type IS NULL OR g.documentType = :type)
              AND (:sourceUid IS NULL OR g.sourceUid = :sourceUid)
              AND (:from IS NULL OR g.generatedAt >= :from)
              AND (:to IS NULL OR g.generatedAt <= :to)
            ORDER BY g.generatedAt DESC
            """)
    Page<GeneratedDocument> listFiltered(
            @Param("companyId") Long companyId,
            @Param("type") DocumentType type,
            @Param("sourceUid") String sourceUid,
            @Param("from") Instant from,
            @Param("to") Instant to,
            Pageable pageable);
}
