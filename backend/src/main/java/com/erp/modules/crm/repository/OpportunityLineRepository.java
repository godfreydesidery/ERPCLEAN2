package com.erp.modules.crm.repository;

import com.erp.modules.crm.domain.entity.OpportunityLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpportunityLineRepository extends JpaRepository<OpportunityLine, Long> {

    Optional<OpportunityLine> findByUid(String uid);

    List<OpportunityLine> findByOpportunityIdOrderByLineNo(Long opportunityId);

    Optional<OpportunityLine> findByUidAndOpportunityId(String uid, Long opportunityId);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM OpportunityLine l WHERE l.opportunityId = :id")
    int findMaxLineNo(@Param("id") Long opportunityId);

    long countByOpportunityId(Long opportunityId);
}
