package com.erp.modules.gl.repository;

import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import java.util.Collection;
import java.util.List;
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

    /** Company-scoped id existence check — used by HR services to prevent cross-tenant GL account binding. */
    boolean existsByIdAndCompanyId(Long id, Long companyId);

    /** Lookup by company + account_code — used by seeders resolving account_id by code. */
    Optional<ChartOfAccount> findByCompanyIdAndAccountCode(Long companyId, String accountCode);

    /**
     * All INCOME and EXPENSE accounts for a company — used by YearEndCloseServiceImpl to select
     * which accounts to zero in the closing entry (ADR-0019 D-3, classify by account_type not code).
     */
    @Query("SELECT a FROM ChartOfAccount a WHERE a.companyId = :companyId AND a.accountType IN :types")
    List<ChartOfAccount> findByCompanyIdAndAccountTypeIn(
            @Param("companyId") Long companyId,
            @Param("types") Collection<AccountType> types);

    /**
     * Load an account by an id already trusted from a scoped parent row (e.g. a petty-cash
     * transaction's captured {@code gl_account_id}, ADR-0050 D-7 PR-B) — used to resolve it back to
     * a uid for a response DTO. Named finder (not the inherited {@code findById}), mirroring
     * {@code CompanyRepository.findScopedById} — the id was already company-scoped-verified at
     * write time, never attacker-supplied at this call site, so this does not trip the
     * confused-deputy guard ({@code TenantScopingRulesTest}).
     */
    @Query("SELECT a FROM ChartOfAccount a WHERE a.id = :id")
    Optional<ChartOfAccount> findScopedById(@Param("id") Long id);
}
