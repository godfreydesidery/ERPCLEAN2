package com.erp.modules.fx.repository;

import com.erp.modules.fx.domain.entity.CurrencyRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Repository for {@code currency_rates} (per-company, effective-dated).
 *
 * <p>Key query: {@link #findEffective} — the as-of lookup used by
 * {@code FxRateService.rateOn(...)}. It returns the row with the greatest
 * {@code effective_date <= asOf} that is {@code active = true} for the given
 * (company, fromCurrency, toCurrency, rateType) tuple.
 *
 * <p>Rate direction reminder: {@code rate} = units of {@code to_currency} per one unit of
 * {@code from_currency}. See {@link com.erp.modules.fx.domain.entity.CurrencyRate}.
 */
public interface CurrencyRateRepository extends JpaRepository<CurrencyRate, Long> {

    Optional<CurrencyRate> findByUid(String uid);

    /**
     * As-of effective-dated lookup: the most-recent active rate on or before {@code asOf}
     * for the given (company, fromCurrency, toCurrency, rateType) tuple.
     *
     * <p>Maps to {@code ix_currency_rates_lookup} (company_id, from_currency, to_currency,
     * effective_date DESC WHERE active = true).
     */
    @Query("""
            SELECT cr FROM CurrencyRate cr
            WHERE  cr.companyId    = :companyId
            AND    cr.fromCurrency = :fromCurrency
            AND    cr.toCurrency   = :toCurrency
            AND    cr.rateType     = :rateType
            AND    cr.effectiveDate <= :asOf
            AND    cr.active       = true
            ORDER BY cr.effectiveDate DESC
            LIMIT 1
            """)
    Optional<CurrencyRate> findEffective(@Param("companyId")    Long companyId,
                                         @Param("fromCurrency") String fromCurrency,
                                         @Param("toCurrency")   String toCurrency,
                                         @Param("rateType")     String rateType,
                                         @Param("asOf")         LocalDate asOf);

    /** List all rates for a company, ordered newest-first (rate-maintenance grid). */
    Page<CurrencyRate> findByCompanyIdOrderByEffectiveDateDescIdDesc(Long companyId, Pageable pageable);

    /** All rates for a company+pair, newest-first (history view). */
    List<CurrencyRate> findByCompanyIdAndFromCurrencyAndToCurrencyOrderByEffectiveDateDesc(
            Long companyId, String fromCurrency, String toCurrency);

    /** Existence check for upsert duplicate-guard (same pair+date+type). */
    boolean existsByCompanyIdAndFromCurrencyAndToCurrencyAndEffectiveDateAndRateType(
            Long companyId, String fromCurrency, String toCurrency,
            LocalDate effectiveDate, String rateType);
}
