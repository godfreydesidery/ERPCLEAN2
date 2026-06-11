package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.DepreciationRunLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepreciationRunLineRepository extends JpaRepository<DepreciationRunLine, Long> {

    List<DepreciationRunLine> findByDepreciationRunId(Long depreciationRunId);
}
