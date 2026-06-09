package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArWriteOff;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArWriteOffRepository extends JpaRepository<ArWriteOff, Long> {

    Optional<ArWriteOff> findByUid(String uid);

    Optional<ArWriteOff> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard support. */
    @Query("SELECT w.companyId FROM ArWriteOff w WHERE w.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<ArWriteOff> findByCompanyId(Long companyId, Pageable pageable);

    List<ArWriteOff> findByArInvoiceId(Long arInvoiceId);
}
