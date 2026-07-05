package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.Supplier;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByUid(String uid);

    Optional<Supplier> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndCode(Long companyId, String code);

    /** Resolve a supplier by its (system-generated) code within a company (bulk import upsert). */
    Optional<Supplier> findByCompanyIdAndCode(Long companyId, String code);

    Page<Supplier> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT s FROM Supplier s
            WHERE s.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(s.displayName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR s.tin = :q
                   OR s.phone = :q
                   OR s.code = :q)
            """)
    Page<Supplier> search(@Param("companyId") Long companyId,
                          @Param("q") String q,
                          Pageable pageable);

    @Query("SELECT s.companyId FROM Supplier s WHERE s.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Resolve a set of supplier ids to their uids, scoped to the company (display enrichment). */
    @Query("SELECT s.uid FROM Supplier s WHERE s.companyId = :companyId AND s.id IN :ids")
    List<String> findUidsByCompanyIdAndIdIn(@Param("companyId") Long companyId,
                                            @Param("ids") Collection<Long> ids);
}
