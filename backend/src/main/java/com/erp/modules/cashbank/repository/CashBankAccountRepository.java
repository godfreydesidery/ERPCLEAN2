package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashBankAccountRepository extends JpaRepository<CashBankAccount, Long> {

    Optional<CashBankAccount> findByUid(String uid);

    Optional<CashBankAccount> findByCompanyIdAndUid(Long companyId, String uid);

    List<CashBankAccount> findByCompanyId(Long companyId);

    List<CashBankAccount> findByCompanyIdAndActive(Long companyId, boolean active);

    /** The company default — enforced unique by partial index. */
    Optional<CashBankAccount> findByCompanyIdAndIsDefaultTrue(Long companyId);

    Optional<CashBankAccount> findByCompanyIdAndGlAccountId(Long companyId, Long glAccountId);

    /** ScopeGuard: resolve uid → companyId (ADR-0016 D-12). */
    @Query("SELECT a.companyId FROM CashBankAccount a WHERE a.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    @Query("SELECT COUNT(t) > 0 FROM CashTransaction t WHERE t.cashBankAccountId = :accountId")
    boolean hasTransactions(@Param("accountId") Long accountId);
}
