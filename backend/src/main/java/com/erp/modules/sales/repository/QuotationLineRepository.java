package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.QuotationLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface QuotationLineRepository extends JpaRepository<QuotationLine, Long> {

    Optional<QuotationLine> findByUid(String uid);

    List<QuotationLine> findByQuotationIdOrderByLineNo(Long quotationId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM QuotationLine l WHERE l.quotationId = :id")
    int findMaxLineNo(@Param("id") Long quotationId);

    Optional<QuotationLine> findByUidAndQuotationId(String uid, Long quotationId);
}
