package com.erp.modules.crm.repository;

import com.erp.modules.crm.domain.entity.Opportunity;
import com.erp.modules.crm.domain.enums.OpportunityStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OpportunityRepository extends JpaRepository<Opportunity, Long> {

    Optional<Opportunity> findByUid(String uid);

    @Query("SELECT o.companyId FROM Opportunity o WHERE o.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<Opportunity> findByCompanyId(Long companyId, Pageable pageable);

    Page<Opportunity> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    /** Pipeline grouping: open opportunities with stage summary data (ADR-0031 D-9). */
    @Query("""
            SELECT ps.uid, ps.name, ps.displayOrder,
                   COUNT(o.id), SUM(o.estimatedValueAmount),
                   SUM(o.estimatedValueAmount * o.winProbability / 100),
                   o.currency
            FROM Opportunity o
            JOIN PipelineStage ps ON ps.id = o.pipelineStageId
            WHERE o.companyId = :companyId
              AND o.branchId = :branchId
              AND o.opportunityStatus = 'OPEN'
            GROUP BY ps.uid, ps.name, ps.displayOrder, o.currency
            ORDER BY ps.displayOrder
            """)
    List<Object[]> pipelineSummaryRaw(@Param("companyId") Long companyId,
                                      @Param("branchId") Long branchId);

    /** Forecast: sum of weighted value for OPEN opportunities closing in the period. */
    @Query("""
            SELECT SUM(o.estimatedValueAmount * o.winProbability / 100), COUNT(o.id), o.currency
            FROM Opportunity o
            WHERE o.companyId = :companyId
              AND o.branchId = :branchId
              AND o.opportunityStatus = 'OPEN'
              AND o.expectedCloseDate BETWEEN :from AND :to
            GROUP BY o.currency
            """)
    List<Object[]> forecastRaw(@Param("companyId") Long companyId,
                               @Param("branchId") Long branchId,
                               @Param("from") LocalDate from,
                               @Param("to") LocalDate to);

    /** KPIs: won and lost counts + avg cycle in a period. */
    @Query("""
            SELECT o.opportunityStatus, COUNT(o.id),
                   AVG(FUNCTION('extract', 'epoch' FROM o.wonAt) - FUNCTION('extract', 'epoch' FROM o.createdAt)) / 86400.0
            FROM Opportunity o
            WHERE o.companyId = :companyId
              AND o.branchId = :branchId
              AND (
                  (o.opportunityStatus = 'WON'  AND o.wonAt  >= :from AND o.wonAt  <= :to)
               OR (o.opportunityStatus = 'LOST' AND o.lostAt >= :from AND o.lostAt <= :to)
              )
            GROUP BY o.opportunityStatus
            """)
    List<Object[]> kpiRaw(@Param("companyId") Long companyId,
                          @Param("branchId") Long branchId,
                          @Param("from") java.time.Instant from,
                          @Param("to") java.time.Instant to);
}
