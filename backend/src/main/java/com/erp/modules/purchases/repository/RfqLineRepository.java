package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.RfqLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqLineRepository extends JpaRepository<RfqLine, Long> {

    List<RfqLine> findByRfqIdOrderByLineNo(Long rfqId);

    Optional<RfqLine> findByUidAndRfqId(String uid, Long rfqId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM RfqLine l WHERE l.rfqId = :rfqId")
    int findMaxLineNo(@Param("rfqId") Long rfqId);
}
