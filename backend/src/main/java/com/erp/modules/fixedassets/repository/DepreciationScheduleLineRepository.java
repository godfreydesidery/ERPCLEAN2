package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.DepreciationScheduleLine;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepreciationScheduleLineRepository extends JpaRepository<DepreciationScheduleLine, Long> {

    Optional<DepreciationScheduleLine> findByUid(String uid);

    List<DepreciationScheduleLine> findByFixedAssetIdOrderByScheduleVersionAscPeriodSeqAsc(Long fixedAssetId);

    /** Fetch all lines for the current (max) schedule version of an asset. */
    @Query("""
            SELECT s FROM DepreciationScheduleLine s
            WHERE s.fixedAssetId = :assetId
              AND s.scheduleVersion = (
                  SELECT MAX(s2.scheduleVersion) FROM DepreciationScheduleLine s2
                  WHERE s2.fixedAssetId = :assetId
              )
            ORDER BY s.periodSeq
            """)
    List<DepreciationScheduleLine> findCurrentVersionByAssetId(@Param("assetId") Long assetId);

    /**
     * Find the unposted schedule line for an asset for the period containing the given date
     * (the period_date is the first day of the fiscal period, and the line maps to the seq
     * period starting on or before that date). Used by the depreciation run to locate eligible lines.
     */
    @Query("""
            SELECT s FROM DepreciationScheduleLine s
            WHERE s.companyId = :companyId
              AND s.fixedAssetId = :assetId
              AND s.posted = false
              AND s.periodDate = :periodDate
              AND s.scheduleVersion = (
                  SELECT MAX(s2.scheduleVersion) FROM DepreciationScheduleLine s2
                  WHERE s2.fixedAssetId = :assetId
              )
            """)
    Optional<DepreciationScheduleLine> findUnpostedForAssetAndDate(
            @Param("companyId") Long companyId,
            @Param("assetId") Long assetId,
            @Param("periodDate") LocalDate periodDate);

    /** Find all unposted lines for IN_SERVICE assets in a company for a given period date. */
    @Query("""
            SELECT s FROM DepreciationScheduleLine s
            JOIN FixedAsset fa ON fa.id = s.fixedAssetId
            WHERE s.companyId = :companyId
              AND s.posted = false
              AND s.periodDate = :periodDate
              AND s.plannedCharge > 0
              AND fa.status = com.erp.modules.fixedassets.domain.enums.FixedAssetStatus.IN_SERVICE
              AND s.scheduleVersion = (
                  SELECT MAX(s2.scheduleVersion) FROM DepreciationScheduleLine s2
                  WHERE s2.fixedAssetId = s.fixedAssetId
              )
            """)
    List<DepreciationScheduleLine> findEligibleForRun(
            @Param("companyId") Long companyId,
            @Param("periodDate") LocalDate periodDate);
}
