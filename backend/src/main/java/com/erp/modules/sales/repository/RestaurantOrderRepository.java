package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.RestaurantOrder;
import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantOrderRepository extends JpaRepository<RestaurantOrder, Long> {

    Optional<RestaurantOrder> findByCompanyIdAndUid(Long companyId, String uid);

    /**
     * The order row, locked for the rest of the transaction. Every action that changes an order
     * (add, send, void, bill, pay) takes this lock first, so two devices pressing Send at once
     * cannot fire the same items twice and two payments cannot both post (ADR-0065 D-6, D-8).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM RestaurantOrder o WHERE o.companyId = :companyId AND o.uid = :uid")
    Optional<RestaurantOrder> findByCompanyIdAndUidForUpdate(@Param("companyId") Long companyId,
                                                             @Param("uid") String uid);

    /** Company-scoped batch lookup (kitchen tickets). */
    List<RestaurantOrder> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);

    List<RestaurantOrder> findByCompanyIdAndBranchIdAndStatusInOrderByOpenedAtAsc(
            Long companyId, Long branchId, Collection<RestaurantOrderStatus> statuses);

    List<RestaurantOrder> findByCompanyIdAndBranchIdAndRestaurantTableIdAndStatusInOrderByOpenedAtAsc(
            Long companyId, Long branchId, Long restaurantTableId,
            Collection<RestaurantOrderStatus> statuses);

    /** Another order already settled by this invoice — guards a reused idempotency key. */
    boolean existsBySalesInvoiceIdAndIdNot(Long salesInvoiceId, Long id);
}
