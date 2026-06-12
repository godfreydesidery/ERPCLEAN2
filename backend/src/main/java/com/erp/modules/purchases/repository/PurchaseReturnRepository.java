package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseReturn;
import com.erp.modules.purchases.domain.enums.PurchaseReturnStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseReturnRepository extends JpaRepository<PurchaseReturn, Long> {

    Optional<PurchaseReturn> findByUid(String uid);

    Page<PurchaseReturn> findByCompanyId(Long companyId, Pageable pageable);

    Page<PurchaseReturn> findByCompanyIdAndStatus(Long companyId, PurchaseReturnStatus status,
                                                   Pageable pageable);

    List<PurchaseReturn> findByGoodsReceiptId(Long goodsReceiptId);

    /** ScopeGuard target-type projection (ADR-0027 D-10). */
    @Query("SELECT r.companyId FROM PurchaseReturn r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
