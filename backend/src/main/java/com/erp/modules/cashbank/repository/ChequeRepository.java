package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.Cheque;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChequeRepository extends JpaRepository<Cheque, Long> {

    Optional<Cheque> findByUid(String uid);

    Optional<Cheque> findByCashBankAccountIdAndChequeNumber(Long accountId, String chequeNumber);

    List<Cheque> findByCompanyId(Long companyId);

    List<Cheque> findByCashBankAccountId(Long accountId);

    /** ScopeGuard: resolve uid → companyId. */
    @Query("SELECT c.companyId FROM Cheque c WHERE c.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
