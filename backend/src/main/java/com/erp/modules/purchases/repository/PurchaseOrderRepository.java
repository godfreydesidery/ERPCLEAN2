package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, Long> {

    Optional<PurchaseOrder> findByUid(String uid);

    Optional<PurchaseOrder> findByCompanyIdAndUid(Long companyId, String uid);

    Page<PurchaseOrder> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Browse page, restricted to the given provenances (V96, K3). The buyer's list passes
     * {MANUAL} so the orders synthesised to anchor direct goods receipts stay out of it; an
     * explicit opt-in passes every value. {@code origin} is NOT NULL with a MANUAL default, so
     * every row that predates V96 is matched by the MANUAL-only form.
     */
    Page<PurchaseOrder> findByCompanyIdAndOriginIn(Long companyId,
                                                   Collection<PurchaseOrderOrigin> origins,
                                                   Pageable pageable);

    Page<PurchaseOrder> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    List<PurchaseOrder> findByCompanyIdAndStatusIn(Long companyId, List<PurchaseOrderStatus> statuses);

    /**
     * Keyword search within a company, restricted to the given provenances (V96, K3) — see
     * {@link #findByCompanyIdAndOriginIn}. Searching must not surface a synthesised order that the
     * browse page hides, otherwise typing a supplier name would leak the very rows the default list
     * exists to suppress.
     */
    @Query("""
            SELECT p FROM PurchaseOrder p
            WHERE p.companyId = :companyId
              AND p.origin IN :origins
              AND (:q IS NULL OR
                   LOWER(p.supplierName) LIKE LOWER(CONCAT('%', :q, '%'))
                   OR p.orderNumber = :q
                   OR p.supplierCode = :q)
            """)
    Page<PurchaseOrder> search(@Param("companyId") Long companyId,
                               @Param("q") String q,
                               @Param("origins") Collection<PurchaseOrderOrigin> origins,
                               Pageable pageable);

    /** ScopeGuard target-type projection (ADR-0011 D-10). */
    @Query("SELECT p.companyId FROM PurchaseOrder p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
