package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.ApDebitNote;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApDebitNoteRepository extends JpaRepository<ApDebitNote, Long> {

    Optional<ApDebitNote> findByUid(String uid);

    /** ScopeGuard projection (ADR-0015 D-12). */
    @Query("SELECT d.companyId FROM ApDebitNote d WHERE d.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<ApDebitNote> findByCompanyIdAndUid(Long companyId, String uid);

    Page<ApDebitNote> findByCompanyId(Long companyId, Pageable pageable);

    Page<ApDebitNote> findByCompanyIdAndSupplierId(Long companyId, Long supplierId, Pageable pageable);
}
