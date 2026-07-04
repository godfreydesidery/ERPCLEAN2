package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.VanReconciliationLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository for {@link VanReconciliationLine} (ADR-0051 D-8).
 */
public interface VanReconciliationLineRepository extends JpaRepository<VanReconciliationLine, Long> {

    List<VanReconciliationLine> findByVanReconciliationIdOrderByLineNoAsc(Long vanReconciliationId);
}
