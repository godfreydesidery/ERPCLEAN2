package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.RestaurantTable;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestaurantTableRepository extends JpaRepository<RestaurantTable, Long> {

    Optional<RestaurantTable> findByCompanyIdAndUid(Long companyId, String uid);

    List<RestaurantTable> findByCompanyIdAndBranchIdOrderBySortOrderAscNameAsc(Long companyId,
                                                                              Long branchId);

    boolean existsByBranchIdAndNameIgnoreCase(Long branchId, String name);

    boolean existsByBranchIdAndNameIgnoreCaseAndIdNot(Long branchId, String name, Long id);

    /** Company-scoped batch lookup, so a stray id can never surface another tenant's table name. */
    List<RestaurantTable> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);
}
