package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockCountLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link StockCountLine} (ADR-0028 D-6).
 */
public interface StockCountLineRepository extends JpaRepository<StockCountLine, Long> {

    /** All lines for a count, ordered by line_no. */
    List<StockCountLine> findByStockCountIdOrderByLineNoAsc(Long stockCountId);

    /** Find by uid. */
    Optional<StockCountLine> findByUid(String uid);
}
