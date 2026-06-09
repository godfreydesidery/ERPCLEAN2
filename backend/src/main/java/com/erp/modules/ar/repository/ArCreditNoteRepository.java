package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArCreditNote;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArCreditNoteRepository extends JpaRepository<ArCreditNote, Long> {

    Optional<ArCreditNote> findByUid(String uid);

    Optional<ArCreditNote> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard support. */
    @Query("SELECT n.companyId FROM ArCreditNote n WHERE n.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<ArCreditNote> findByCompanyId(Long companyId, Pageable pageable);

    List<ArCreditNote> findByCompanyIdAndCustomerId(Long companyId, Long customerId);

    List<ArCreditNote> findByArInvoiceId(Long arInvoiceId);
}
