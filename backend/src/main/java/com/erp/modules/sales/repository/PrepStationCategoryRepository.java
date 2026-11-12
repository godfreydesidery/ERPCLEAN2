package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PrepStationCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PrepStationCategoryRepository extends JpaRepository<PrepStationCategory, Long> {

    List<PrepStationCategory> findByCompanyIdAndBranchIdOrderByCategoryAsc(Long companyId,
                                                                          Long branchId);

    /** One branch's route for a category, matched case-insensitively ({@code key} is lower-case). */
    @Query("SELECT c FROM PrepStationCategory c WHERE c.companyId = :companyId"
            + " AND c.branchId = :branchId AND lower(c.category) = :key")
    Optional<PrepStationCategory> findRoute(@Param("companyId") Long companyId,
                                            @Param("branchId") Long branchId,
                                            @Param("key") String key);

    /** Clears a branch's routing before the routing screen writes its replacement set. */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query("DELETE FROM PrepStationCategory c WHERE c.companyId = :companyId AND c.branchId = :branchId")
    int deleteByCompanyIdAndBranchId(@Param("companyId") Long companyId,
                                     @Param("branchId") Long branchId);
}
