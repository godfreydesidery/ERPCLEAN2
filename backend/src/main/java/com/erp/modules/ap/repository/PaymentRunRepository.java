package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.PaymentRun;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRunRepository extends JpaRepository<PaymentRun, Long> {

    Optional<PaymentRun> findByUid(String uid);

    Optional<PaymentRun> findByCompanyIdAndUid(Long companyId, String uid);

    Page<PaymentRun> findByCompanyId(Long companyId, Pageable pageable);
}
