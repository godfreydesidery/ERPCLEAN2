package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.time.BusinessZone;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every active company's fiscal calendar one year ahead (ACC-01).
 *
 * <p><b>Why this exists.</b> Company provisioning seeds only the calendar year it was created in
 * ({@link FiscalCalendarService#seedCurrentYear}), and nothing ever opened the next one. On 1 January
 * every automatic posting — sales, COGS, goods receipts — would have been refused for want of a
 * period (and silently dropped by the outbox posters), while receipts, payments and stock
 * adjustments failed in front of cashiers and storekeepers.
 *
 * <p><b>What it does.</b> For each ACTIVE company it calls
 * {@link FiscalCalendarService#ensureCurrentAndNextYear}: a year covers today (in the company's own
 * time zone) and the year after it exists. Idempotent — once both years exist it changes nothing.
 * Standing rule: provisioning over data migrations, so this is application code, not Flyway.
 *
 * <p><b>When.</b> Once when the application is ready (after {@code BootstrapRunner} and the other
 * {@code ApplicationRunner}s — so an upgraded estate is healed on its first boot with no manual
 * step), then daily on {@code erp.gl.fiscal-year-rollover.cron} (default 00:30 server time).
 * Scheduling is enabled platform-wide by {@code OutboxSchedulingConfig}.
 *
 * <p><b>Failure handling.</b> Each company runs in its own transaction (the service call goes
 * through the transactional proxy), under a SYSTEM principal for that company so the audit row is
 * attributed to it. One company's failure is logged and never stops the others or the application.
 * Two instances racing collide on the unique (company, year_code) key and one simply fails, logged.
 */
@Component
public class FiscalYearRolloverJob {

    private static final Logger log = LoggerFactory.getLogger(FiscalYearRolloverJob.class);

    private final CompanyRepository companies;
    private final FiscalCalendarService calendar;
    private final boolean enabled;

    public FiscalYearRolloverJob(CompanyRepository companies,
                                 FiscalCalendarService calendar,
                                 @Value("${erp.gl.fiscal-year-rollover.enabled:true}") boolean enabled) {
        this.companies = companies;
        this.calendar  = calendar;
        this.enabled   = enabled;
    }

    /** First pass on boot: heals an estate that is already missing next year's calendar. */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (enabled) {
            rollOverAllCompanies(Instant.now());
        }
    }

    /** Daily pass — the next year is opened as soon as the current one starts. */
    @Scheduled(cron = "${erp.gl.fiscal-year-rollover.cron:0 30 0 * * *}")
    public void daily() {
        if (enabled) {
            rollOverAllCompanies(Instant.now());
        }
    }

    /**
     * Runs the rollover for every ACTIVE company as of {@code now}.
     *
     * @return the number of fiscal years opened across all companies
     */
    public int rollOverAllCompanies(Instant now) {
        List<Company> all;
        try {
            all = companies.findAll();
        } catch (RuntimeException ex) {
            log.warn("Fiscal year rollover: could not list companies.", ex);
            return 0;
        }
        int opened = 0;
        for (Company company : all) {
            if (company.getStatus() != MasterStatus.ACTIVE) {
                continue;
            }
            opened += rollOver(company, now);
        }
        if (opened > 0) {
            log.info("Fiscal year rollover: opened {} fiscal year(s).", opened);
        }
        return opened;
    }

    private int rollOver(Company company, Instant now) {
        LocalDate today = now.atZone(zoneOf(company)).toLocalDate();
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(company.getId(), null));
        try {
            List<FiscalYearDto> created = calendar.ensureCurrentAndNextYear(company.getId(), today);
            return created.size();
        } catch (RuntimeException ex) {
            log.error("Fiscal year rollover failed for company={}: {}", company.getId(), ex.toString(), ex);
            return 0;
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }
    }

    /** The company's zone; blank or invalid falls back to Africa/Dar_es_Salaam (never UTC). */
    private static ZoneId zoneOf(Company company) {
        return BusinessZone.parse(company.getTimeZone());
    }
}
