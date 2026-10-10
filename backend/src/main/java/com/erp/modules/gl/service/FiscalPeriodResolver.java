package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.platform.common.api.AccountingSetupException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a (companyId, postingDate) to the OPEN fiscal period that covers that date
 * (ADR-0013 D-4/D-3, BR-GL-03). Called by GLPostingService for every post — manual and automatic.
 *
 * <p>A refusal says which of three things went wrong (ACC-29), because each has a different fix:
 * <ul>
 *   <li>no period covers the date — the fiscal year has not been set up;</li>
 *   <li>the period covering the date is CLOSED — it is named, so the accountant knows which one
 *       to reopen;</li>
 *   <li>more than one OPEN period covers the date — overlapping fiscal years (possible on data
 *       created before ACC-09); refusing beats guessing which year the entry belongs to.</li>
 * </ul>
 * Each is an {@link AccountingSetupException}: accountants see the message, operators a plain
 * "ask your accountant" sentence (ACC-21).
 */
@Component
public class FiscalPeriodResolver {

    private static final Logger log = LoggerFactory.getLogger(FiscalPeriodResolver.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH);

    private final FiscalPeriodRepository periods;
    private final FiscalYearRepository years;

    public FiscalPeriodResolver(FiscalPeriodRepository periods, FiscalYearRepository years) {
        this.periods = periods;
        this.years = years;
    }

    /**
     * Returns the OPEN period covering {@code postingDate} for the company.
     *
     * @throws AccountingSetupException if no period covers the date, the covering period is CLOSED,
     *         or overlapping fiscal years make the date ambiguous (BR-GL-03)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FiscalPeriod resolveOpen(Long companyId, LocalDate postingDate) {
        List<FiscalPeriod> covering = periods.findAllCoveringDate(companyId, postingDate);
        List<FiscalPeriod> open = covering.stream()
                .filter(p -> p.getStatus() == PeriodStatus.OPEN)
                .toList();

        if (open.size() == 1) {
            return open.get(0);
        }
        String day = postingDate.format(DAY);
        if (open.size() > 1) {
            log.error("Overlapping open fiscal periods for company={} on {}: {} periods",
                    companyId, postingDate, open.size());
            throw new AccountingSetupException(
                    "More than one open fiscal period covers " + day + " because two fiscal years"
                            + " overlap, so this entry cannot be placed in a year. Close the periods"
                            + " of the fiscal year that should not cover this date, then try again.");
        }
        if (covering.isEmpty()) {
            throw new AccountingSetupException(
                    "No fiscal period is set up for " + day + ". Open the fiscal year that covers"
                            + " this date under General Ledger, Fiscal Periods, then try again.");
        }
        String period = describe(covering.get(0));
        throw new AccountingSetupException(
                "The fiscal period " + period + ", which covers " + day + ", is closed. Reopen that"
                        + " period, or use a date in an open period.",
                "The accounting period for " + day + " is closed, so this cannot be recorded."
                        + " Ask your accountant.");
    }

    /** "October 2026 (period 10 of FY2026)" — the name an accountant finds on the periods screen. */
    private String describe(FiscalPeriod period) {
        String month = period.getName() != null && !period.getName().isBlank()
                ? period.getName()
                : period.getStartDate().format(MONTH);
        String yearCode = years.findByCompanyIdAndId(period.getCompanyId(), period.getFiscalYearId())
                .map(FiscalYear::getYearCode)
                .orElse(null);
        return yearCode != null
                ? month + " (period " + period.getPeriodNo() + " of " + yearCode + ")"
                : month + " (period " + period.getPeriodNo() + ")";
    }
}
