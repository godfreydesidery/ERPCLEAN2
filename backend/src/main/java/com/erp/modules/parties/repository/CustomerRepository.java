package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.Customer;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerRepository extends JpaRepository<Customer, Long> {

    Optional<Customer> findByUid(String uid);

    Optional<Customer> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    Page<Customer> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Search by name (case-insensitive prefix), TIN, phone, or code within a company.
     */
    @Query("""
            SELECT c FROM Customer c
            WHERE c.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(c.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR c.tin = :q
                   OR c.phone = :q
                   OR c.code = :q)
            """)
    Page<Customer> search(@Param("companyId") Long companyId,
                          @Param("q") String q,
                          Pageable pageable);

    /**
     * Resolves a customer uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * (ADR-0006 D-10). Single-column JPQL projection avoids loading the full entity.
     */
    @Query("SELECT c.companyId FROM Customer c WHERE c.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
