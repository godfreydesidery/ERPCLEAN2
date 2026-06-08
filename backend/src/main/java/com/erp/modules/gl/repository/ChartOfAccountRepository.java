package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChartOfAccountRepository extends JpaRepository<ChartOfAccount, Long> {

    Optional<ChartOfAccount> findByUid(String uid);

    /** Company-scoped uid lookup — used by service read paths and ScopeGuard (ADR-0013 D-10). */
    Optional<ChartOfAccount> findByCompanyIdAndUid(Long companyId, String uid);

    boolean existsByCompanyIdAndAccountCode(Long companyId, String accountCode);

    Page<ChartOfAccount> findByCompanyId(Long companyId, Pageable pageable);

    /** Single-column projection for ScopeGuard case "account" (ADR-0013 D-10). */
    @Query("SELECT a.companyId FROM ChartOfAccount a WHERE a.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** True if any journal_lines row references this account (BR-GL-07, no-delete guard). */
    @Query("SELECT COUNT(l) > 0 FROM JournalLine l WHERE l.accountId = :accountId")
    boolean hasPostings(@Param("accountId") Long accountId);

    /** Lookup by company + account_code — used by seeders resolving account_id by code. */
    Optional<ChartOfAccount> findByCompanyIdAndAccountCode(Long companyId, String accountCode);
}
