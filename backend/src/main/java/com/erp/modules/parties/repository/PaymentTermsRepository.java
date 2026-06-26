package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.PaymentTerms;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Repository for the PaymentTerms master (D-2). */
public interface PaymentTermsRepository extends JpaRepository<PaymentTerms, Long> {

    Optional<PaymentTerms> findByUid(String uid);

    Page<PaymentTerms> findByCompanyId(Long companyId, Pageable pageable);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Tenant-scoped existence check for the numeric FK used on customer/supplier records. */
    boolean existsByCompanyIdAndId(Long companyId, Long id);

    @Query("""
            SELECT pt FROM PaymentTerms pt
            WHERE pt.companyId = :companyId
              AND (lower(pt.name) LIKE lower(concat('%',:q,'%'))
                OR lower(pt.code) LIKE lower(concat('%',:q,'%')))
            """)
    Page<PaymentTerms> search(@Param("companyId") Long companyId,
                               @Param("q") String q,
                               Pageable pageable);
}
