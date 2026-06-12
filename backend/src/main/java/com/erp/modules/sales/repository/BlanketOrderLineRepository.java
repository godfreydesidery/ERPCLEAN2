package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.BlanketOrderLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlanketOrderLineRepository extends JpaRepository<BlanketOrderLine, Long> {

    Optional<BlanketOrderLine> findByUid(String uid);

    @Query("SELECT l.companyId FROM BlanketOrderLine l WHERE l.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<BlanketOrderLine> findByBlanketOrderId(Long blanketOrderId);

    @Query("SELECT l FROM BlanketOrderLine l WHERE l.blanketOrderId = :blanketOrderId AND l.productId = :productId")
    Optional<BlanketOrderLine> findByBlanketOrderIdAndProductId(
            @Param("blanketOrderId") Long blanketOrderId,
            @Param("productId") Long productId);
}
