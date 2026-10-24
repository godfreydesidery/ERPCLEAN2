package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.RestaurantOrderLine;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RestaurantOrderLineRepository extends JpaRepository<RestaurantOrderLine, Long> {

    List<RestaurantOrderLine> findByRestaurantOrderIdOrderByLineNoAsc(Long restaurantOrderId);

    List<RestaurantOrderLine> findByRestaurantOrderIdInOrderByLineNoAsc(Collection<Long> orderIds);

    Optional<RestaurantOrderLine> findByRestaurantOrderIdAndUid(Long restaurantOrderId, String uid);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM RestaurantOrderLine l "
            + "WHERE l.restaurantOrderId = :orderId")
    int findMaxLineNo(@Param("orderId") Long orderId);

    @Query("SELECT COALESCE(MAX(CAST(l.fireRound AS Integer)), 0) FROM RestaurantOrderLine l "
            + "WHERE l.restaurantOrderId = :orderId")
    int findMaxFireRound(@Param("orderId") Long orderId);
}
