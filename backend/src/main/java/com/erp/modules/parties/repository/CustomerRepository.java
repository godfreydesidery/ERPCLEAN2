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

    /**
     * Company-scoped PK lookup. Tenant-safe finder for a service resolving a customer by the id
     * carried on an already-scoped entity (e.g. {@code order.getCustomerId()}) — avoids a bare
     * {@code findById}, which {@code TenantScopingRulesTest} flags as confused-deputy-prone.
     */
    Optional<Customer> findByCompanyIdAndId(Long companyId, Long id);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Resolve a customer by its (system-generated) code within a company (bulk import upsert). */
    Optional<Customer> findByCompanyIdAndCode(Long companyId, String code);

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
