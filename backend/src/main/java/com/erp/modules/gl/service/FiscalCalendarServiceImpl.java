package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.FiscalYear;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.FiscalYearRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FiscalCalendarServiceImpl implements FiscalCalendarService {

    private static final Logger log = LoggerFactory.getLogger(FiscalCalendarServiceImpl.class);

    private final FiscalYearRepository years;
    private final FiscalPeriodRepository periods;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public FiscalCalendarServiceImpl(FiscalYearRepository years,
                                      FiscalPeriodRepository periods,
                                      CompanyRepository companies,
                                      ScopeGuard scopeGuard,
                                      AuditService audit) {
        this.years      = years;
        this.periods    = periods;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public FiscalYearDto openFiscalYear(OpenFiscalYearRequest req) {
        Company company = requireCompanyByUid(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), company.getId());

        if (years.findByCompanyIdAndYearCode(company.getId(), req.yearCode()).isPresent()) {
            throw new ConflictException(
                    "Fiscal year " + req.yearCode() + " already exists for this company.");
        }

        FiscalYear year = createYearWithPeriods(
                company.getId(), req.yearCode(), req.startMonth(), req.calendarYear(), actorId());

        audit.record(AuditEvent.of(AuditActions.GL_PERIOD_OPEN, "fiscal_years",
                        year.getId(), year.getUid())
                .detail(Map.of("yearCode", req.yearCode())));

        return toYearDto(year);
    }

    @Override
    @Transactional(readOnly = true)
    public FiscalYearDto getFiscalYearByUid(String uid) {
        FiscalYear year = requireYearByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), year.getCompanyId());
        return toYearDto(year);
    }

    @Override
    @Transactional(readOnly = true)
    public List<FiscalYearDto> listFiscalYears(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return years.findByCompanyIdOrderByStartDateDesc(companyId).stream()
                .map(this::toYearDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FiscalPeriodDto> listPeriods(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return periods.findByCompanyIdOrderByStartDateAsc(companyId).stream()
                .map(this::toPeriodDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<FiscalPeriodDto> listPeriodsForYear(String fiscalYearUid) {
        FiscalYear year = requireYearByUid(fiscalYearUid);
        scopeGuard.assertCanActIn(RequestContext.get(), year.getCompanyId());
        return periods.findByFiscalYearIdOrderByPeriodNo(year.getId()).stream()
                .map(this::toPeriodDto).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public FiscalPeriodDto getPeriodByUid(String uid) {
        FiscalPeriod period = requirePeriodByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), period.getCompanyId());
        return toPeriodDto(period);
    }

    @Override
    public FiscalPeriodDto closePeriod(String periodUid) {
        FiscalPeriod period = requirePeriodByUid(periodUid);
        scopeGuard.assertCanActIn(RequestContext.get(), period.getCompanyId());

        if (period.getStatus() == PeriodStatus.CLOSED) {
            throw new ConflictException("Period " + periodUid + " is already CLOSED.");
        }
        period.setStatus(PeriodStatus.CLOSED);
        period.setClosedAt(Instant.now());
        period.setClosedBy(actorId());
        period.setUpdatedAt(Instant.now());
        period.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.GL_PERIOD_CLOSE, "fiscal_periods",
                        period.getId(), period.getUid())
                .detail(Map.of("periodNo", String.valueOf(period.getPeriodNo()))));

        return toPeriodDto(period);
    }

    @Override
    public FiscalPeriodDto reopenPeriod(String periodUid) {
        FiscalPeriod period = requirePeriodByUid(periodUid);
        scopeGuard.assertCanActIn(RequestContext.get(), period.getCompanyId());

        if (period.getStatus() == PeriodStatus.OPEN) {
            throw new ConflictException("Period " + periodUid + " is already OPEN.");
        }
        period.setStatus(PeriodStatus.OPEN);
        period.setClosedAt(null);
        period.setClosedBy(null);
        period.setUpdatedAt(Instant.now());
        period.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.GL_PERIOD_OPEN, "fiscal_periods",
                        period.getId(), period.getUid())
                .detail(Map.of("periodNo", String.valueOf(period.getPeriodNo()))));

        return toPeriodDto(period);
    }

    @Override
    public void seedCurrentYear(Long companyId) {
        int calendarYear = Year.now().getValue();
        String yearCode  = "FY" + calendarYear;
        if (years.findByCompanyIdAndYearCode(companyId, yearCode).isPresent()) {
            return; // already seeded
        }
        createYearWithPeriods(companyId, yearCode, 1, calendarYear, null);
        log.info("Seeded fiscal year {} for company {}.", yearCode, companyId);
    }

    // -------------------------------------------------------------------------

    private FiscalYear createYearWithPeriods(Long companyId, String yearCode, int startMonth,
                                              int calendarYear, Long createdBy) {
        LocalDate startDate = LocalDate.of(calendarYear, startMonth, 1);
        // end_date = last day of month 12 (from startMonth, wrapping year if needed)
        LocalDate endDate   = startDate.plusMonths(12).minusDays(1);

        FiscalYear year = years.save(new FiscalYear(
                companyId, yearCode, startMonth, startDate, endDate, createdBy));

        List<FiscalPeriod> savedPeriods = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            LocalDate pStart = startDate.plusMonths(i);
            LocalDate pEnd   = pStart.plusMonths(1).minusDays(1);
            savedPeriods.add(periods.save(
                    new FiscalPeriod(companyId, year.getId(), i + 1, pStart, pEnd, createdBy)));
        }
        return year;
    }

    private FiscalYear requireYearByUid(String uid) {
        return years.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FiscalYear", uid));
    }

    private FiscalPeriod requirePeriodByUid(String uid) {
        return periods.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FiscalPeriod", uid));
    }

    private Company requireCompanyByUid(String uid) {
        return companies.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Company", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private FiscalYearDto toYearDto(FiscalYear y) {
        return new FiscalYearDto(y.getId(), y.getUid(), y.getCompanyId(),
                y.getYearCode(), y.getStartMonth(), y.getStartDate(), y.getEndDate(), y.getStatus());
    }

    private FiscalPeriodDto toPeriodDto(FiscalPeriod p) {
        return new FiscalPeriodDto(p.getId(), p.getUid(), p.getCompanyId(),
                p.getFiscalYearId(), p.getPeriodNo(), p.getStartDate(), p.getEndDate(),
                p.getStatus(), p.getClosedAt());
    }
}
