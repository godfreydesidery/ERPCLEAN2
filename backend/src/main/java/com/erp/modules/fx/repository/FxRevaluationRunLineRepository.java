package com.erp.modules.fx.repository;

import com.erp.modules.fx.domain.entity.FxRevaluationRunLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FxRevaluationRunLineRepository extends JpaRepository<FxRevaluationRunLine, Long> {

    List<FxRevaluationRunLine> findByFxRevaluationRunId(Long fxRevaluationRunId);
}
