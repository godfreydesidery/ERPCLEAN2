package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesReturnLine;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesReturnLineRepository extends JpaRepository<SalesReturnLine, Long> {

    List<SalesReturnLine> findBySalesReturnIdOrderByLineNo(Long salesReturnId);
}
