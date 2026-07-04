package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.CashCount;
import com.erp.modules.cashbank.domain.enums.CashCountStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CashCountRepository extends JpaRepository<CashCount, Long> {

    Optional<CashCount> findByUid(String uid);

    /** A live (non-reconciled) count already exists for this till + date — used to block a second
     *  count whose variance would otherwise post to GL/cash-book twice (ADR-0050 D-7 review guard). */
    boolean existsByCashAccountIdAndBusinessDateAndStatusIn(
            Long cashAccountId, LocalDate businessDate, Collection<CashCountStatus> statuses);

    Optional<CashCount> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard: resolve uid -> companyId (ADR-0050 D-7.6). */
    @Query("SELECT c.companyId FROM CashCount c WHERE c.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<CashCount> findByCashAccountIdOrderByBusinessDateDescIdDesc(Long cashAccountId);

    List<CashCount> findByCompanyIdOrderByBusinessDateDescIdDesc(Long companyId);
}
