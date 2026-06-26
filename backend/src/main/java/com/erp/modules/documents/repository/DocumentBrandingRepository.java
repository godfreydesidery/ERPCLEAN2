package com.erp.modules.documents.repository;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DocumentBrandingRepository extends JpaRepository<DocumentBranding, Long> {

    Optional<DocumentBranding> findByUid(String uid);

    Optional<DocumentBranding> findByCompanyId(Long companyId);

    /** Ownership guard: load a branding row only when it belongs to the given company. */
    Optional<DocumentBranding> findByIdAndCompanyId(Long id, Long companyId);

    /** ScopeGuard support (ADR-0023 D-9). */
    @Query("SELECT b.companyId FROM DocumentBranding b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
