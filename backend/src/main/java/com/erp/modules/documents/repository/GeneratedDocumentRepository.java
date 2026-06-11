package com.erp.modules.documents.repository;

import com.erp.modules.documents.domain.entity.GeneratedDocument;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GeneratedDocumentRepository
        extends JpaRepository<GeneratedDocument, Long>,
                JpaSpecificationExecutor<GeneratedDocument> {

    Optional<GeneratedDocument> findByUid(String uid);

    /** ScopeGuard support (ADR-0023 D-9). */
    @Query("SELECT g.companyId FROM GeneratedDocument g WHERE g.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<GeneratedDocument> findByCompanyId(Long companyId, Pageable pageable);
}
