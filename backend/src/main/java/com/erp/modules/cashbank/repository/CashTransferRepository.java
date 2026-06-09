package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.CashTransfer;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashTransferRepository extends JpaRepository<CashTransfer, Long> {

    Optional<CashTransfer> findByUid(String uid);

    List<CashTransfer> findByCompanyId(Long companyId);

    /** ScopeGuard: resolve uid → companyId. */
    @Query("SELECT t.companyId FROM CashTransfer t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
