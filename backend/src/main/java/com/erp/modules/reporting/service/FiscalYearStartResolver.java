package com.erp.modules.reporting.service;

import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the start of the fiscal year containing a date (ADR-0018 D-6, step 1).
 *
 * <p>Shared by the Balance Sheet (which splits the equity fold into prior-years and current-year
 * earnings at this date) and the Statement of Changes in Equity (which must split it identically,
 * or its closing column would not equal the Balance Sheet's equity lines).
 */
@Component
@Transactional(readOnly = true)
public class FiscalYearStartResolver {

    private final FiscalPeriodRepository fiscalPeriodRepo;

    public FiscalYearStartResolver(FiscalPeriodRepository fiscalPeriodRepo) {
        this.fiscalPeriodRepo = fiscalPeriodRepo;
    }

    /**
     * The fiscal-year start date containing {@code date} for {@code companyId}. If no fiscal year
     * covers the date, falls back to Jan 1 of that calendar year.
     */
    public LocalDate fyStart(Long companyId, LocalDate date) {
        List<FiscalPeriod> allPeriods = fiscalPeriodRepo.findByCompanyIdOrderByStartDateAsc(companyId);
        // Find the fiscal year covering this date
        Long fyId = allPeriods.stream()
                .filter(p -> !p.getStartDate().isAfter(date) && !p.getEndDate().isBefore(date))
                .map(FiscalPeriod::getFiscalYearId)
                .findFirst()
                .orElse(null);
        if (fyId == null) {
            // No fiscal year covers this date — fall back to calendar year start
            return LocalDate.of(date.getYear(), 1, 1);
        }
        // Period 1 of that fiscal year holds the FY start date
        return allPeriods.stream()
                .filter(p -> fyId.equals(p.getFiscalYearId()))
                .map(FiscalPeriod::getStartDate)
                .min(LocalDate::compareTo)
                .orElse(LocalDate.of(date.getYear(), 1, 1));
    }
}
