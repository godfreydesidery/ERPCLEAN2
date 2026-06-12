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

    /** ScopeGuard resolution (D-10). */
    @Query("SELECT st.companyId FROM StockTransfer st WHERE st.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
