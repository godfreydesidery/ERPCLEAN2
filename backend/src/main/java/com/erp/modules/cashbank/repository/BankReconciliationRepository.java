package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.BankReconciliation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankReconciliationRepository extends JpaRepository<BankReconciliation, Long> {

    Optional<BankReconciliation> findByUid(String uid);

    Optional<BankReconciliation> findByCompanyIdAndUid(Long companyId, String uid);

    List<BankReconciliation> findByCashBankAccountId(Long accountId);

    /**
     * The account's latest reconciliation in {@code status} (latest statement date, then latest
     * id) — the previous COMPLETED one is where the next statement's opening balance comes from.
     */
    Optional<BankReconciliation> findFirstByCashBankAccountIdAndStatusOrderByStatementDateDescIdDesc(
            Long accountId, com.erp.modules.cashbank.domain.enums.ReconciliationStatus status);

    /** ScopeGuard: resolve uid → companyId. */
    @Query("SELECT r.companyId FROM BankReconciliation r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
