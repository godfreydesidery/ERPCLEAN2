package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockTransferLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link StockTransferLine} (ADR-0028 D-5).
 */
public interface StockTransferLineRepository extends JpaRepository<StockTransferLine, Long> {

    /** All lines for a given transfer header, ordered by line_no. */
    List<StockTransferLine> findByStockTransferIdOrderByLineNoAsc(Long stockTransferId);
}
