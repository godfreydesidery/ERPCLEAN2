package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PrepStationCategory;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrepStationCategoryRepository extends JpaRepository<PrepStationCategory, Long> {

    List<PrepStationCategory> findByCompanyIdAndBranchIdOrderByCategoryAsc(Long companyId,
                                                                          Long branchId);

    /** Clears a branch's routing before the routing screen writes its replacement set. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM PrepStationCategory c WHERE c.companyId = :companyId AND c.branchId = :branchId")
    int deleteByCompanyIdAndBranchId(@Param("companyId") Long companyId,
                                     @Param("branchId") Long branchId);
}
