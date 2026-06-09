package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.CashTransaction;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashTransactionRepository extends JpaRepository<CashTransaction, Long> {

    Optional<CashTransaction> findByUid(String uid);

    Optional<CashTransaction> findByCompanyIdAndUid(Long companyId, String uid);

    List<CashTransaction> findByCashBankAccountIdOrderByTxnDateAscIdAsc(Long cashBankAccountId);

    List<CashTransaction> findByCompanyIdAndSourceRef(Long companyId, String sourceRef);

    /** ScopeGuard: resolve uid → companyId. */
    @Query("SELECT t.companyId FROM CashTransaction t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Book balance of an account: SUM(IN) - SUM(OUT).
     * Returns null when no transactions exist (no rows) — callers treat null as zero.
     */
    @Query("""
            SELECT SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END)
            FROM CashTransaction t
            WHERE t.cashBankAccountId = :accountId
            """)
    java.math.BigDecimal bookBalance(@Param("accountId") Long accountId);

    /** Cleared book balance for a reconciliation (the completion check, D-6). */
    @Query("""
            SELECT SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END)
            FROM CashTransaction t
            WHERE t.clearedInReconciliationId = :reconciliationId
            """)
    java.math.BigDecimal clearedBookBalance(@Param("reconciliationId") Long reconciliationId);

    List<CashTransaction> findByClearedInReconciliationId(Long reconciliationId);

    List<CashTransaction> findByCashBankAccountIdAndClearedFalse(Long cashBankAccountId);

    List<CashTransaction> findByUidIn(List<String> uids);
}
