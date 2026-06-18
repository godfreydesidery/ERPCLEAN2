package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.entity.PayeBand;
import com.erp.modules.hr.domain.entity.PayeBandSet;
import com.erp.modules.hr.domain.entity.StatutoryRateSet;
import com.erp.modules.hr.domain.enums.StatutoryBasis;
import com.erp.modules.hr.domain.enums.StatutoryRateType;
import com.erp.modules.hr.repository.PayeBandRepository;
import com.erp.modules.hr.repository.PayeBandSetRepository;
import com.erp.modules.hr.repository.StatutoryRateSetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Stateless TZ statutory computation engine (ADR-0032 D-4, NFR-HR-02).
 * All arithmetic uses HALF_UP rounding to TZS with no decimal places (scale=0 result),
 * but intermediate working maintains scale=4 to avoid accumulated rounding errors.
 */
@Component
public class StatutoryCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PayeBandSetRepository    payeBandSets;
    private final PayeBandRepository       payeBands;
    private final StatutoryRateSetRepository rateSets;

    public StatutoryCalculator(PayeBandSetRepository payeBandSets,
                                PayeBandRepository payeBands,
                                StatutoryRateSetRepository rateSets) {
        this.payeBandSets = payeBandSets;
        this.payeBands    = payeBands;
        this.rateSets     = rateSets;
    }

    /** Result record returned to PayrollRunServiceImpl for each employee line. */
    public record StatutoryResult(
            BigDecimal paye,
            BigDecimal nssfEmployee,
            BigDecimal nssfEmployer,
            BigDecimal wcfEmployer,
            BigDecimal sdlEmployer,
            BigDecimal heslb,
            String payeBandSetUid,
            String nssfSetUid,
            String wcfSetUid,
            String sdlSetUid,
            String heslbSetUid
    ) {}

    /**
     * Compute all statutory deductions for one employee line.
     *
     * @param companyId          the company
     * @param payDate            the pay date (used to select in-force rate sets)
     * @param grossAmount        total gross (basic + all EARNING items) in TZS
     * @param basicAmount        basic salary only (used for HESLB basis = BASIC)
     * @param pensionable        pensionable pay (used for NSSF basis = PENSIONABLE; typically = basic)
     * @param headcount          total number of employees this run (for SDL threshold check)
     * @param unpaidLeaveDays    calendar days of approved unpaid leave in this pay period (>= 0)
     * @param periodWorkingDays  total working days in the pay period (> 0; typically 22 for monthly)
     */
    public StatutoryResult compute(Long companyId, LocalDate payDate,
                                    BigDecimal grossAmount, BigDecimal basicAmount,
                                    BigDecimal pensionable, int headcount,
                                    BigDecimal unpaidLeaveDays, int periodWorkingDays) {
        // FR-HR-13 pro-rata: reduce gross by proportion of unpaid leave
        // grossForPeriod = grossAmount × (periodWorkingDays − unpaidLeaveDays) / periodWorkingDays
        BigDecimal grossForPeriod = grossAmount;
        if (unpaidLeaveDays != null && unpaidLeaveDays.compareTo(BigDecimal.ZERO) > 0
                && periodWorkingDays > 0) {
            BigDecimal workingDays = BigDecimal.valueOf(periodWorkingDays);
            BigDecimal factor = workingDays.subtract(unpaidLeaveDays)
                    .divide(workingDays, 4, RoundingMode.HALF_UP);
            // clamp to [0, 1] — unpaid days cannot exceed working days
            if (factor.compareTo(BigDecimal.ZERO) < 0) factor = BigDecimal.ZERO;
            grossForPeriod = grossAmount.multiply(factor).setScale(4, RoundingMode.HALF_UP);
        }

        BigDecimal paye = computePaye(companyId, payDate, grossForPeriod);
        // WCF/SDL use GROSS basis → apply pro-rated grossForPeriod; NSSF uses PENSIONABLE (not reduced by leave)
        StatutoryRateResult nssf  = computeNssf(companyId, payDate, pensionable, grossForPeriod, basicAmount);
        StatutoryRateResult wcf   = computeEmployerOnly(companyId, payDate, StatutoryRateType.WCF,   grossForPeriod, basicAmount, headcount);
        StatutoryRateResult sdl   = computeEmployerOnly(companyId, payDate, StatutoryRateType.SDL,   grossForPeriod, basicAmount, headcount);
        StatutoryRateResult heslb = computeEmployeeOnly(companyId, payDate, StatutoryRateType.HESLB, grossForPeriod, basicAmount, headcount);

        return new StatutoryResult(
                paye,
                nssf.employeeAmt(),
                nssf.employerAmt(),
                wcf.employerAmt(),
                sdl.employerAmt(),
                heslb.employeeAmt(),
                resolvePayeBandSetUid(companyId, payDate).orElse(null),
                nssf.setUid(),
                wcf.setUid(),
                sdl.setUid(),
                heslb.setUid()
        );
    }

    // --- PAYE banded computation ---

    private BigDecimal computePaye(Long companyId, LocalDate payDate, BigDecimal gross) {
        List<PayeBandSet> sets = payeBandSets.findInForce(companyId, payDate);
        if (sets.isEmpty()) {
            return BigDecimal.ZERO;
        }
        PayeBandSet set = sets.get(0);
        List<PayeBand> bands = payeBands.findByPayeBandSetIdOrderByBandNoAsc(set.getId());
        if (bands.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // Find the highest band whose lowerBound <= gross
        PayeBand applicableBand = bands.get(0);
        for (PayeBand band : bands) {
            if (gross.compareTo(band.getLowerBound()) >= 0) {
                applicableBand = band;
            }
        }
        // tax = cumulativeFixedTax + (gross - lowerBound) * marginalRate / 100
        BigDecimal excess = gross.subtract(applicableBand.getLowerBound());
        BigDecimal marginal = excess.multiply(applicableBand.getMarginalRate())
                                    .divide(HUNDRED, 4, RoundingMode.HALF_UP);
        BigDecimal tax = applicableBand.getCumulativeFixedTax().add(marginal);
        // Round to whole TZS
        return tax.setScale(0, RoundingMode.HALF_UP);
    }

    private Optional<String> resolvePayeBandSetUid(Long companyId, LocalDate payDate) {
        List<PayeBandSet> sets = payeBandSets.findInForce(companyId, payDate);
        return sets.isEmpty() ? Optional.empty() : Optional.of(sets.get(0).getUid());
    }

    // --- Rate-based helpers ---

    private record StatutoryRateResult(BigDecimal employeeAmt, BigDecimal employerAmt, String setUid) {}

    /** NSSF has a single rate-set entry with both employeeRate and employerRate. */
    private StatutoryRateResult computeNssf(Long companyId, LocalDate payDate,
                                             BigDecimal pensionable,
                                             BigDecimal gross, BigDecimal basic) {
        Optional<StatutoryRateSet> opt = resolveSet(companyId, payDate, StatutoryRateType.NSSF);
        if (opt.isEmpty()) return new StatutoryRateResult(BigDecimal.ZERO, BigDecimal.ZERO, null);
        StatutoryRateSet set = opt.get();
        BigDecimal base = basisAmount(set.getBasis(), gross, basic, pensionable);
        BigDecimal empAmt = applyRate(set.getEmployeeRate(), base);
        BigDecimal erAmt  = applyRate(set.getEmployerRate(), base);
        return new StatutoryRateResult(empAmt, erAmt, set.getUid());
    }

    private StatutoryRateResult computeEmployerOnly(Long companyId, LocalDate payDate,
                                                     StatutoryRateType type,
                                                     BigDecimal gross, BigDecimal basic,
                                                     int headcount) {
        Optional<StatutoryRateSet> opt = resolveSet(companyId, payDate, type);
        if (opt.isEmpty()) return new StatutoryRateResult(BigDecimal.ZERO, BigDecimal.ZERO, null);
        StatutoryRateSet set = opt.get();
        // Check headcount threshold (SDL: only if >= threshold, usually 10)
        if (set.getHeadcountThreshold() != null && headcount < set.getHeadcountThreshold()) {
            return new StatutoryRateResult(BigDecimal.ZERO, BigDecimal.ZERO, set.getUid());
        }
        BigDecimal base = basisAmount(set.getBasis(), gross, basic, basic);
        BigDecimal amt = applyRate(set.getEmployerRate(), base);
        return new StatutoryRateResult(BigDecimal.ZERO, amt, set.getUid());
    }

    private StatutoryRateResult computeEmployeeOnly(Long companyId, LocalDate payDate,
                                                     StatutoryRateType type,
                                                     BigDecimal gross, BigDecimal basic,
                                                     int headcount) {
        Optional<StatutoryRateSet> opt = resolveSet(companyId, payDate, type);
        if (opt.isEmpty()) return new StatutoryRateResult(BigDecimal.ZERO, BigDecimal.ZERO, null);
        StatutoryRateSet set = opt.get();
        if (set.getHeadcountThreshold() != null && headcount < set.getHeadcountThreshold()) {
            return new StatutoryRateResult(BigDecimal.ZERO, BigDecimal.ZERO, set.getUid());
        }
        BigDecimal base = basisAmount(set.getBasis(), gross, basic, basic);
        BigDecimal amt = applyRate(set.getEmployeeRate(), base);
        return new StatutoryRateResult(amt, BigDecimal.ZERO, set.getUid());
    }

    private Optional<StatutoryRateSet> resolveSet(Long companyId, LocalDate payDate, StatutoryRateType type) {
        List<StatutoryRateSet> found = rateSets.findInForce(companyId, type, payDate);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private BigDecimal basisAmount(StatutoryBasis basis, BigDecimal gross, BigDecimal basic, BigDecimal pensionable) {
        if (basis == null) return gross;
        return switch (basis) {
            case BASIC        -> basic;
            case PENSIONABLE  -> pensionable;
            case GROSS        -> gross;
        };
    }

    private BigDecimal applyRate(BigDecimal rate, BigDecimal base) {
        if (rate == null || base == null) return BigDecimal.ZERO;
        BigDecimal result = base.multiply(rate).divide(HUNDRED, 4, RoundingMode.HALF_UP);
        return result.setScale(0, RoundingMode.HALF_UP);
    }
}
