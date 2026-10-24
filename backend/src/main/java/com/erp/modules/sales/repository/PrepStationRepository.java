package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PrepStation;
import com.erp.platform.common.domain.MasterStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PrepStationRepository extends JpaRepository<PrepStation, Long> {

    Optional<PrepStation> findByCompanyIdAndUid(Long companyId, String uid);

    List<PrepStation> findByCompanyIdAndBranchIdOrderByNameAsc(Long companyId, Long branchId);

    List<PrepStation> findByCompanyIdAndBranchIdAndStatus(Long companyId, Long branchId,
                                                          MasterStatus status);

    boolean existsByBranchIdAndNameIgnoreCase(Long branchId, String name);

    boolean existsByBranchIdAndNameIgnoreCaseAndIdNot(Long branchId, String name, Long id);

    /** Company-scoped batch lookup (slip and line labels). */
    List<PrepStation> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);
}
