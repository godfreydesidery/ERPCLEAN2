package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.VanReconciliation;
import com.erp.modules.stock.domain.enums.VanReconciliationStatus;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link VanReconciliation} (ADR-0051 D-8).
 */
public interface VanReconciliationRepository extends JpaRepository<VanReconciliation, Long> {

    Optional<VanReconciliation> findByUid(String uid);

    /** Tenant-scoped paged list for the van-reconciliation management endpoint. */
    Page<VanReconciliation> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    /**
     * One-live-worksheet-per-van-per-day guard (mirrors the DB partial unique
     * {@code uq_van_reconciliation_van_date}): true when a non-CANCELLED worksheet already exists
     * for this van + business date.
     */
    boolean existsByVanLocationIdAndBusinessDateAndStatusNot(
            Long vanLocationId, LocalDate businessDate, VanReconciliationStatus status);

    /** ScopeGuard resolution (D-10 pattern). */
    @Query("SELECT v.companyId FROM VanReconciliation v WHERE v.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
