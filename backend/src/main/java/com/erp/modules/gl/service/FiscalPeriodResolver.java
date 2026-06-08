package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import java.time.LocalDate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves a (companyId, postingDate) to the OPEN fiscal period that covers that date
 * (ADR-0013 D-4/D-3, BR-GL-03). Called by GLPostingService for every post — manual and automatic.
 */
@Component
public class FiscalPeriodResolver {

    private final FiscalPeriodRepository periods;

    public FiscalPeriodResolver(FiscalPeriodRepository periods) {
        this.periods = periods;
    }

    /**
     * Returns the OPEN period covering {@code postingDate} for the company.
     * Throws if none found or the period is CLOSED (BR-GL-03).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public FiscalPeriod resolveOpen(Long companyId, LocalDate postingDate) {
        return periods.findOpenPeriodForDate(companyId, postingDate, PeriodStatus.OPEN)
                .orElseThrow(() -> new IllegalStateException(
                        "No OPEN fiscal period found for company " + companyId
                                + " and date " + postingDate
                                + ". Check that a fiscal year/period exists and is OPEN (BR-GL-03)."));
    }
}
