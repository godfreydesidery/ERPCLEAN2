package com.erp.modules.documents.repository;

import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.domain.enums.DocumentType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {

    Optional<DocumentTemplate> findByUid(String uid);

    List<DocumentTemplate> findByCompanyId(Long companyId);

    Optional<DocumentTemplate> findByCompanyIdAndDocumentType(Long companyId, DocumentType documentType);

    /** ScopeGuard support (ADR-0023 D-9). */
    @Query("SELECT t.companyId FROM DocumentTemplate t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
