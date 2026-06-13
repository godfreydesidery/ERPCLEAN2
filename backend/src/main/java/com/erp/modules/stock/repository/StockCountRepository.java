package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockCount;
import com.erp.modules.stock.domain.enums.StockCountStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockCount} (ADR-0028 D-6/D-10).
 */
public interface StockCountRepository extends JpaRepository<StockCount, Long> {

    Optional<StockCount> findByUid(String uid);

    /** Tenant-scoped paged list. */
    Page<StockCount> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    /** Filtered by status. */
    Page<StockCount> findByCompanyIdAndBranchIdAndStatus(
            Long companyId, Long branchId, StockCountStatus status, Pageable pageable);

    /** ScopeGuard resolution (D-10). */
    @Query("SELECT sc.companyId FROM StockCount sc WHERE sc.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
