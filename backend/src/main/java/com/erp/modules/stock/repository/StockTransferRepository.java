package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockTransfer;
import com.erp.modules.stock.domain.enums.StockTransferStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockTransfer} (ADR-0028 D-5/D-10).
 */
public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {

    Optional<StockTransfer> findByUid(String uid);

    /** Tenant-scoped paged list. */
    Page<StockTransfer> findByCompanyId(Long companyId, Pageable pageable);

    /** Filtered by status. */
    Page<StockTransfer> findByCompanyIdAndStatus(Long companyId, StockTransferStatus status, Pageable pageable);

    /**
     * STK-19: the transfer list's filters. Every filter is optional (null = not applied):
     * status; transfers coming INTO a branch, going OUT of one, or touching it either way; a
     * transfer-date range; and a transfer-number fragment (lower-cased by the caller).
     */
    @Query("""
            SELECT t FROM StockTransfer t
            WHERE t.companyId = :companyId
              AND (:status IS NULL OR t.status = :status)
              AND (:destBranchId IS NULL OR t.destBranchId = :destBranchId)
              AND (:sourceBranchId IS NULL OR t.sourceBranchId = :sourceBranchId)
              AND (:anyBranchId IS NULL
                   OR t.sourceBranchId = :anyBranchId OR t.destBranchId = :anyBranchId)
              AND (CAST(:fromDate AS LocalDate) IS NULL OR t.transferDate >= :fromDate)
              AND (CAST(:toDate AS LocalDate) IS NULL OR t.transferDate <= :toDate)
              AND (:numberLike IS NULL OR LOWER(t.transferNumber) LIKE :numberLike)
            """)
    Page<StockTransfer> search(@Param("companyId") Long companyId,
                               @Param("status") StockTransferStatus status,
                               @Param("destBranchId") Long destBranchId,
                               @Param("sourceBranchId") Long sourceBranchId,
                               @Param("anyBranchId") Long anyBranchId,
                               @Param("fromDate") java.time.LocalDate fromDate,
                               @Param("toDate") java.time.LocalDate toDate,
                               @Param("numberLike") String numberLike,
                               Pageable pageable);

    /** ScopeGuard resolution (D-10). */
    @Query("SELECT st.companyId FROM StockTransfer st WHERE st.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
