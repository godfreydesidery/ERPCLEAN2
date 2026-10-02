package com.erp.modules.sales.service;

import com.erp.modules.ar.domain.dto.ArBalanceDto;
import com.erp.modules.ar.domain.dto.ArUnconvertedAmountDto;
import com.erp.modules.ar.service.ArBalanceService;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.money.CurrencyConversionService;
import com.erp.platform.common.money.FxRateNotFoundException;
import com.erp.platform.common.money.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * Credit-limit exposure in the company BASE currency (ADR-0014 D-9, ADR-0040 D-5; owner ruling
 * 2026-10-02 "per currency, convert only reliable rows").
 *
 * <pre>
 * exposure = AR base balance            (base rows + reliable foreign rows at their STORED rate)
 *          + Σ unconverted foreign AR    (V62-filled rows, converted at TODAY's rate)
 *          + the new document's gross    (converted at today's rate when foreign)
 * breached = a needed rate is missing   (fail closed — see below)
 *         OR exposure &gt; credit limit (the limit converted at today's rate when not in base)
 * </pre>
 *
 * <p><b>Fail closed.</b> When any amount the decision needs is in a currency with no exchange
 * rate on or before today, the exposure cannot be known. The check never drops that amount (that
 * would understate the exposure) and never counts it at par; it reports the currency in
 * {@link Assessment#missingRateCurrencies()} and marks the limit as breached, so the caller's
 * normal over-limit path runs: blocked without {@code SALES.CREDIT.OVERRIDE}, allowed and audited
 * with it. The callers tell the user which currency needs a rate.
 *
 * <p>Rates are read through the platform {@link CurrencyConversionService} (the FX rate table's
 * cross-module seam), never another module's entity or service.
 */
@Component
public class CreditExposureCalculator {

    private final ArBalanceService arBalance;
    private final CurrencyConversionService fx;

    public CreditExposureCalculator(ArBalanceService arBalance, CurrencyConversionService fx) {
        this.arBalance = arBalance;
        this.fx = fx;
    }

    /**
     * @param exposure              base-currency exposure; when rates are missing it covers only
     *                              the amounts that could be converted
     * @param creditLimitBase       the credit limit in base, or {@code null} if its rate is missing
     * @param baseCurrency          company base currency
     * @param missingRateCurrencies currencies with no rate on or before today (sorted)
     * @param breached              {@code true} when over the limit OR any rate is missing
     */
    public record Assessment(BigDecimal exposure, BigDecimal creditLimitBase, String baseCurrency,
                             List<String> missingRateCurrencies, boolean breached) {
        public boolean rateMissing() {
            return !missingRateCurrencies.isEmpty();
        }
    }

    /**
     * @param newDocAmount   gross of the invoice/order being finalised/confirmed
     * @param newDocCurrency its currency
     * @param creditLimit    the customer's (present, positive) credit limit
     */
    public Assessment assess(Long companyId, Long customerId, BigDecimal newDocAmount,
                             String newDocCurrency, Money creditLimit, LocalDate today) {
        ArBalanceDto balance = arBalance.currentBalance(companyId, customerId);
        String base = balance.currency();
        TreeSet<String> missing = new TreeSet<>();

        BigDecimal exposure = balance.balance() != null ? balance.balance() : BigDecimal.ZERO;
        for (ArUnconvertedAmountDto u : balance.unconverted()) {
            exposure = exposure.add(orZero(toBase(u.amount(), u.currency(), base, companyId,
                    today, missing)));
        }
        if (newDocAmount != null) {
            exposure = exposure.add(orZero(toBase(newDocAmount, newDocCurrency, base, companyId,
                    today, missing)));
        }

        String limitCurrency = creditLimit.getCurrency() != null
                ? CurrencyCode.value(creditLimit.getCurrency()) : base;
        BigDecimal limitBase = toBase(creditLimit.getAmount(), limitCurrency, base, companyId,
                today, missing);

        boolean breached = !missing.isEmpty()
                || (limitBase != null && exposure.compareTo(limitBase) > 0);
        return new Assessment(exposure, limitBase, base, new ArrayList<>(missing), breached);
    }

    /** Converts at today's rate; a missing rate is recorded and returns {@code null}. */
    private BigDecimal toBase(BigDecimal amount, String currency, String base, Long companyId,
                              LocalDate today, TreeSet<String> missing) {
        if (amount == null || amount.signum() == 0) {
            return BigDecimal.ZERO;
        }
        if (currency == null || currency.equals(base)) {
            return amount;
        }
        try {
            return fx.toBase(amount, currency, companyId, today).baseAmount();
        } catch (FxRateNotFoundException e) {
            missing.add(currency);
            return null;
        }
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /** Friendly sentence naming the currencies that need a rate (no internal detail). */
    public static String missingRateSentence(List<String> currencies) {
        String list = String.join(", ", currencies);
        return "The credit limit could not be checked because there is no exchange rate for "
                + list + ". Add " + (currencies.size() == 1 ? "a " + list + " rate" : "these rates")
                + " under Currency rates and try again.";
    }
}
