package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.CashTransaction;
import java.time.LocalDate;
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

    /**
     * Book balance of an account as-of a business date: SUM(IN) - SUM(OUT) over transactions dated
     * on or before {@code asOf} (ADR-0050 D-7.2 — the derived "expected" figure for a cash count).
     * Returns null when no transactions exist on/before that date — callers treat null as zero.
     */
    @Query("""
            SELECT SUM(CASE WHEN t.direction = 'IN' THEN t.amount ELSE -t.amount END)
            FROM CashTransaction t
            WHERE t.cashBankAccountId = :accountId AND t.txnDate <= :asOf
            """)
    java.math.BigDecimal bookBalanceAsOf(@Param("accountId") Long accountId, @Param("asOf") LocalDate asOf);

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
