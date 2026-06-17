package com.erp.modules.fx.repository;

import com.erp.modules.fx.domain.entity.CompanyCurrency;
import com.erp.platform.common.money.CurrencyCode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for the per-company currency allow-list (ADR-0039 D-7).
 */
public interface CompanyCurrencyRepository extends JpaRepository<CompanyCurrency, Long> {

    Optional<CompanyCurrency> findByUid(String uid);

    /** All rows for the company (active + inactive). */
    List<CompanyCurrency> findByCompanyIdOrderByCurrencyCode(Long companyId);

    /** Active rows only — the enabled set. */
    List<CompanyCurrency> findByCompanyIdAndActiveTrueOrderByCurrencyCode(Long companyId);

    /** Find a specific (company, code) pair regardless of active flag. */
    Optional<CompanyCurrency> findByCompanyIdAndCurrencyCode(Long companyId, CurrencyCode currencyCode);

    /** Find a specific (company, code) pair that is active. */
    Optional<CompanyCurrency> findByCompanyIdAndCurrencyCodeAndActiveTrue(
            Long companyId, CurrencyCode currencyCode);

    /** The current default document currency row for the company. */
    Optional<CompanyCurrency> findByCompanyIdAndIsDefaultTrue(Long companyId);

    /** Whether any row exists for this company/code (used for idempotent seeding). */
    boolean existsByCompanyIdAndCurrencyCode(Long companyId, CurrencyCode currencyCode);
}
