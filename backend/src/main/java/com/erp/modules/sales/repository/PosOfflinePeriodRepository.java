package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosOfflinePeriod;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PosOfflinePeriodRepository extends JpaRepository<PosOfflinePeriod, Long> {

    Optional<PosOfflinePeriod> findByCompanyIdAndClientKey(Long companyId, String clientKey);

    Page<PosOfflinePeriod> findByCompanyIdAndBranchIdOrderByStartedAtDesc(Long companyId, Long branchId,
                                                                          Pageable pageable);
}
