package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayeBand;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayeBandRepository extends JpaRepository<PayeBand, Long> {

    Optional<PayeBand> findByUid(String uid);

    @Query("SELECT b.companyId FROM PayeBand b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<PayeBand> findByPayeBandSetIdOrderByBandNoAsc(Long payeBandSetId);
}
