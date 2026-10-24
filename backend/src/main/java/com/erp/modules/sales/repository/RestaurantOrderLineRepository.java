package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.RestaurantOrderLine;
import com.erp.modules.sales.domain.enums.RestaurantOrderLineStatus;
import java.time.Instant;
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

    /**
     * A station's open work for the kitchen display (ADR-0066 D-6): sent (or voided after
     * sending) and not yet bumped, sent since [since] so a forgotten line cannot hang forever.
     */
    @Query("SELECT l FROM RestaurantOrderLine l WHERE l.companyId = :companyId "
            + "AND l.prepStationId = :stationId AND l.readyAt IS NULL "
            + "AND l.status IN :statuses AND l.sentAt >= :since "
            + "ORDER BY l.sentAt ASC, l.lineNo ASC")
    List<RestaurantOrderLine> findOpenForStation(@Param("companyId") Long companyId,
                                                 @Param("stationId") Long stationId,
                                                 @Param("statuses") Collection<RestaurantOrderLineStatus> statuses,
                                                 @Param("since") Instant since);

    List<RestaurantOrderLine> findByCompanyIdAndUidIn(Long companyId, Collection<String> uids);

    /** A station's lines sent or voided in (since, until] — the slip feed (ADR-0066 D-6). */
    @Query("SELECT l FROM RestaurantOrderLine l WHERE l.companyId = :companyId "
            + "AND l.prepStationId = :stationId AND ("
            + "(l.sentAt > :since AND l.sentAt <= :until) OR "
            + "(l.voidedAt > :since AND l.voidedAt <= :until)) "
            + "ORDER BY l.restaurantOrderId ASC, l.lineNo ASC")
    List<RestaurantOrderLine> findForStationWindow(@Param("companyId") Long companyId,
                                                   @Param("stationId") Long stationId,
                                                   @Param("since") Instant since,
                                                   @Param("until") Instant until);

    List<RestaurantOrderLine> findByCompanyIdAndParentLineIdIn(Long companyId, Collection<Long> parentIds);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM RestaurantOrderLine l "
            + "WHERE l.restaurantOrderId = :orderId")
    int findMaxLineNo(@Param("orderId") Long orderId);

    @Query("SELECT COALESCE(MAX(CAST(l.fireRound AS Integer)), 0) FROM RestaurantOrderLine l "
            + "WHERE l.restaurantOrderId = :orderId")
    int findMaxFireRound(@Param("orderId") Long orderId);
}
