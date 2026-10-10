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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FiscalCalendarServiceImpl implements FiscalCalendarService {

    private static final Logger log = LoggerFactory.getLogger(FiscalCalendarServiceImpl.class);

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Audit "trigger" values for a year opened by the system rather than by a person. */
    static final String TRIGGER_ROLLOVER       = "automatic-rollover";
    static final String TRIGGER_YEAR_END_CLOSE = "year-end-close";

    /** Upper bound on years created in one catch-up pass — a guard, not an expected case. */
    private static final int MAX_CATCH_UP_YEARS = 5;

    /** fiscal_years.year_code is VARCHAR(12). */
    private static final int YEAR_CODE_MAX = 12;

    /** "FY2026" → ("FY", "2026"); "2026" → ("", "2026"). */
    private static final Pattern TRAILING_YEAR = Pattern.compile("^(.*?)(\\d{4})$");

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
        if (req.startMonth() < 1 || req.startMonth() > 12) {
            throw new IllegalArgumentException("The start month must be between 1 and 12.");
        }

        // ACC-09: years must not overlap — two open periods on one date break every posting.
        LocalDate startDate = LocalDate.of(req.calendarYear(), req.startMonth(), 1);
        LocalDate endDate   = startDate.plusMonths(12).minusDays(1);
        List<FiscalYear> overlapping = years.findOverlapping(company.getId(), startDate, endDate);
        if (!overlapping.isEmpty()) {
            FiscalYear clash = overlapping.get(0);
            throw new ConflictException(
                    "Fiscal year " + req.yearCode() + " (" + startDate.format(DAY) + " to "
                            + endDate.format(DAY) + ") would overlap fiscal year "
                            + clash.getYearCode() + " (" + clash.getStartDate().format(DAY) + " to "
                            + clash.getEndDate().format(DAY) + "). Fiscal years cannot overlap;"
                            + " start the new year the day after the existing one ends.");
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
            throw new ConflictException("This period is already CLOSED.");
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
            throw new ConflictException("This period is already OPEN.");
        }
        // ACC-15: a period of a CLOSED year stays closed. Reopening it alone would let postings land
        // in a year whose P&L was already rolled into Retained Earnings, while the year still reads
        // CLOSED. Reopening the year reverses the closing journal and reopens its periods properly.
        FiscalYear year = years
                .findByCompanyIdAndId(period.getCompanyId(), period.getFiscalYearId())
                .orElse(null);
        if (year != null && year.getStatus() == PeriodStatus.CLOSED) {
            throw new ConflictException(
                    "This period belongs to fiscal year " + year.getYearCode() + ", which is"
                            + " closed. Reopen the fiscal year first (Year-End Close, Reopen Year);"
                            + " that reverses the year-end closing entry and reopens its periods.");
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
    // ACC-01: fiscal-year rollover (provisioning, not a data migration)
    // -------------------------------------------------------------------------

    @Override
    public List<FiscalYearDto> ensureCurrentAndNextYear(Long companyId, LocalDate today) {
        List<FiscalYear> all = new ArrayList<>(years.findByCompanyIdOrderByStartDateDesc(companyId));
        List<FiscalYear> created = new ArrayList<>();

        if (all.isEmpty()) {
            // A company with no calendar at all (seedCurrentYear never ran): give it the calendar
            // year containing today, exactly as company provisioning would have.
            LocalDate start = LocalDate.of(today.getYear(), 1, 1);
            addIfCreated(created, createAutoYear(companyId, "FY" + today.getYear(), start, all,
                    TRIGGER_ROLLOVER));
        }

        FiscalYear covering = covering(all, today);
        if (covering == null && !all.isEmpty()) {
            FiscalYear latest = latestByEnd(all);
            if (latest.getEndDate().isBefore(today)) {
                // The books ran past the last year (e.g. the server was down over New Year): roll
                // forward year by year, each starting the day after the previous one ends, so the
                // calendar stays contiguous.
                FiscalYear base = latest;
                for (int i = 0; i < MAX_CATCH_UP_YEARS && base != null
                        && base.getEndDate().isBefore(today); i++) {
                    base = createSuccessor(base, all, TRIGGER_ROLLOVER);
                    addIfCreated(created, base);
                }
                covering = covering(all, today);
            } else {
                // Today is before the first year or in a gap between years. Placing a year there
                // automatically could collide with what the accountant intends — leave it to them.
                log.warn("Fiscal year rollover: no fiscal year covers {} for company={} and it is"
                        + " not after the latest year; not created automatically.", today, companyId);
            }
        }

        if (covering != null && !hasYearStartingAfter(all, covering.getEndDate())) {
            addIfCreated(created, createSuccessor(latestByEnd(all), all, TRIGGER_ROLLOVER));
        }
        return created.stream().map(this::toYearDto).toList();
    }

    @Override
    public Optional<FiscalYearDto> ensureFollowingYear(String fiscalYearUid) {
        FiscalYear year = requireYearByUid(fiscalYearUid);
        List<FiscalYear> all =
                new ArrayList<>(years.findByCompanyIdOrderByStartDateDesc(year.getCompanyId()));
        if (hasYearStartingAfter(all, year.getEndDate())) {
            return Optional.empty();
        }
        return Optional.ofNullable(createSuccessor(latestByEnd(all), all, TRIGGER_YEAR_END_CLOSE))
                .map(this::toYearDto);
    }

    /**
     * Creates the year that starts the day after {@code base} ends: same start month, twelve
     * monthly periods. Returns {@code null} (and logs) when it cannot be placed safely — the base
     * does not end on a month end, or the slot is already (partly) taken by another year.
     */
    private FiscalYear createSuccessor(FiscalYear base, List<FiscalYear> all, String trigger) {
        LocalDate start = base.getEndDate().plusDays(1);
        if (start.getDayOfMonth() != 1) {
            log.warn("Fiscal year rollover: {} for company={} does not end on a month end;"
                    + " the next year must be opened by hand.", base.getYearCode(), base.getCompanyId());
            return null;
        }
        String code = nextYearCode(base, start, all);
        if (code == null) {
            log.warn("Fiscal year rollover: no free year code after {} for company={};"
                    + " the next year must be opened by hand.", base.getYearCode(), base.getCompanyId());
            return null;
        }
        return createAutoYear(base.getCompanyId(), code, start, all, trigger);
    }

    /** Creates and audits one automatically opened year, unless it would overlap another. */
    private FiscalYear createAutoYear(Long companyId, String code, LocalDate start,
                                      List<FiscalYear> all, String trigger) {
        LocalDate end = start.plusMonths(12).minusDays(1);
        if (!years.findOverlapping(companyId, start, end).isEmpty()) {
            log.warn("Fiscal year rollover: {} to {} would overlap an existing year for company={};"
                    + " not created.", start, end, companyId);
            return null;
        }
        FiscalYear year = createYearWithPeriods(
                companyId, code, start.getMonthValue(), start.getYear(), actorId());
        all.add(year);
        audit.record(AuditEvent.of(AuditActions.GL_PERIOD_OPEN, "fiscal_years",
                        year.getId(), year.getUid())
                .detail(Map.of("yearCode", code, "trigger", trigger)));
        log.info("Fiscal year rollover: opened {} ({} to {}) for company={} [{}].",
                code, start, end, companyId, trigger);
        return year;
    }

    /**
     * The code for the year after {@code base}. Follows the base's own convention when it ends in a
     * four-digit year ("FY2026" → "FY2027", "2026" → "2027"); otherwise "FY" + the start year. A
     * taken code gets a "-2", "-3"… suffix. {@code null} when nothing fits the 12-character column.
     */
    private String nextYearCode(FiscalYear base, LocalDate start, List<FiscalYear> all) {
        Matcher m = TRAILING_YEAR.matcher(base.getYearCode());
        String candidate = m.matches()
                ? m.group(1) + (Integer.parseInt(m.group(2))
                        + (start.getYear() - base.getStartDate().getYear()))
                : "FY" + start.getYear();
        if (candidate.length() > YEAR_CODE_MAX) {
            candidate = "FY" + start.getYear();
        }
        for (int n = 1; n <= 9; n++) {
            String code = n == 1 ? candidate : candidate + "-" + n;
            if (code.length() > YEAR_CODE_MAX) {
                return null;
            }
            boolean taken = all.stream().anyMatch(y -> y.getYearCode().equalsIgnoreCase(code))
                    || years.findByCompanyIdAndYearCode(base.getCompanyId(), code).isPresent();
            if (!taken) {
                return code;
            }
        }
        return null;
    }

    private static FiscalYear covering(List<FiscalYear> all, LocalDate date) {
        return all.stream()
                .filter(y -> !y.getStartDate().isAfter(date) && !y.getEndDate().isBefore(date))
                .findFirst()
                .orElse(null);
    }

    private static FiscalYear latestByEnd(List<FiscalYear> all) {
        return all.stream().max(Comparator.comparing(FiscalYear::getEndDate)).orElseThrow();
    }

    private static boolean hasYearStartingAfter(List<FiscalYear> all, LocalDate date) {
        return all.stream().anyMatch(y -> y.getStartDate().isAfter(date));
    }

    private static void addIfCreated(List<FiscalYear> created, FiscalYear year) {
        if (year != null) {
            created.add(year);
        }
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
                y.getYearCode(), y.getStartMonth(), y.getStartDate(), y.getEndDate(), y.getStatus(),
                y.getClosedAt(), y.getClosedBy(), y.getClosingJournalUid(),
                y.getReopenedAt(), y.getReopenedBy());
    }

    private FiscalPeriodDto toPeriodDto(FiscalPeriod p) {
        return new FiscalPeriodDto(p.getId(), p.getUid(), p.getCompanyId(),
                p.getFiscalYearId(), p.getPeriodNo(), p.getStartDate(), p.getEndDate(),
                p.getStatus(), p.getClosedAt());
    }
}
