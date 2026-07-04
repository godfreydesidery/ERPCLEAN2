package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.PettyCashTransaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PettyCashTransactionRepository extends JpaRepository<PettyCashTransaction, Long> {

    Optional<PettyCashTransaction> findByUid(String uid);

    List<PettyCashTransaction> findByPettyCashFundIdOrderByTxnDateDescIdDesc(Long pettyCashFundId);

    /** ScopeGuard: resolve uid -> companyId (ADR-0050 D-7 PR-B). */
    @Query("SELECT t.companyId FROM PettyCashTransaction t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
