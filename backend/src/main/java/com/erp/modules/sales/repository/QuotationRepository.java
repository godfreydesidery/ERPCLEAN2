package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.Quotation;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotationRepository extends JpaRepository<Quotation, Long> {

    Optional<Quotation> findByUid(String uid);

    @Query("SELECT q.companyId FROM Quotation q WHERE q.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<Quotation> findByCompanyId(Long companyId, Pageable pageable);
}
