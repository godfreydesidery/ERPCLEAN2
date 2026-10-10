package com.erp.modules.gl.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.domain.dto.FiscalYearDto;
import com.erp.modules.gl.domain.dto.OpenFiscalYearRequest;
import com.erp.modules.gl.domain.enums.PeriodStatus;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link FiscalCalendarService} (ADR-0013, gl.md FR-GL-14..16, BR-GL-03).
 *
 * <p>Covers:
 * <ul>
 *   <li>Open a fiscal year with a custom start month (e.g. July) → 12 contiguous monthly periods
 *       with correct period_no + start/end dates, wrapping the calendar year.</li>
 *   <li>seedCurrentYear generates a full 12-period year for the current calendar year.</li>
 *   <li>Close a period; closing an already-closed period rejected; reopen; reopening an open
 *       period rejected.</li>
 *   <li>Cross-tenant isolation: a company-A principal cannot close a company-B period.</li>
 * </ul>
 */
class FiscalCalendarServiceIT extends PostgresIntegrationTest {

    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private FiscalYearRolloverJob rolloverJob;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Long rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Fiscal IT Org"));
        company = companies.save(new Company(org, "FYIT", "Fiscal IT Co"));
        Branch branch = branches.save(new Branch(company, "FYB1", "Fiscal IT Branch"));

        AppUser root = new AppUser("fyit_root", passwordEncoder.encode("RootPass1!"), "Fiscal Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "fyit_root", true, company.getId(), branch.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    // ---------------------------------------------------------------------------
    // Fiscal year generation
    // ---------------------------------------------------------------------------

    @Test
    void openFiscalYear_customStartMonthJuly_generates12ContiguousPeriods() {
        FiscalYearDto fy = fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "FY2026", 7, 2026));

        assertThat(fy.startMonth()).isEqualTo(7);
        assertThat(fy.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        // 12 months from Jul 2026 → ends 30 Jun 2027
        assertThat(fy.endDate()).isEqualTo(LocalDate.of(2027, 6, 30));

        List<FiscalPeriodDto> periods = fiscalCalendarService.listPeriodsForYear(fy.uid());
        assertThat(periods).hasSize(12);
        assertThat(periods).extracting(FiscalPeriodDto::periodNo)
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12);

        // Period 1 = July 2026; period 12 = June 2027; all OPEN on creation
        FiscalPeriodDto p1 = periods.get(0);
        assertThat(p1.startDate()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(p1.endDate()).isEqualTo(LocalDate.of(2026, 7, 31));
        FiscalPeriodDto p12 = periods.get(11);
        assertThat(p12.startDate()).isEqualTo(LocalDate.of(2027, 6, 1));
        assertThat(p12.endDate()).isEqualTo(LocalDate.of(2027, 6, 30));
        assertThat(periods).allMatch(p -> p.status() == PeriodStatus.OPEN);
    }

    @Test
    void seedCurrentYear_generatesTwelvePeriods() {
        fiscalCalendarService.seedCurrentYear(company.getId());

        List<FiscalPeriodDto> periods = fiscalCalendarService.listPeriods(company.getId());
        assertThat(periods).hasSize(12);
        assertThat(fiscalCalendarService.listFiscalYears(company.getId())).hasSize(1);
    }

    // ---------------------------------------------------------------------------
    // Open / close / reopen
    // ---------------------------------------------------------------------------

    @Test
    void closePeriod_marksClosed_andClosingAgainRejected() {
        fiscalCalendarService.seedCurrentYear(company.getId());
        FiscalPeriodDto period = fiscalCalendarService.listPeriods(company.getId()).get(0);

        FiscalPeriodDto closed = fiscalCalendarService.closePeriod(period.uid());
        assertThat(closed.status()).isEqualTo(PeriodStatus.CLOSED);

        assertThatThrownBy(() -> fiscalCalendarService.closePeriod(period.uid()))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void reopenPeriod_marksOpen_andReopeningOpenPeriodRejected() {
        fiscalCalendarService.seedCurrentYear(company.getId());
        FiscalPeriodDto period = fiscalCalendarService.listPeriods(company.getId()).get(0);

        fiscalCalendarService.closePeriod(period.uid());
        FiscalPeriodDto reopened = fiscalCalendarService.reopenPeriod(period.uid());
        assertThat(reopened.status()).isEqualTo(PeriodStatus.OPEN);

        assertThatThrownBy(() -> fiscalCalendarService.reopenPeriod(period.uid()))
                .isInstanceOf(ConflictException.class);
    }

    // ---------------------------------------------------------------------------
    // ACC-09: overlapping fiscal years are refused (query runs against real Postgres)
    // ---------------------------------------------------------------------------

    @Test
    void openFiscalYear_overlappingExistingYearUnderAnotherCode_rejected() {
        fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "FY2031", 1, 2031));

        // Same dates, different code — the unique (company, year_code) key does not catch this.
        assertThatThrownBy(() -> fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "2031", 1, 2031)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("overlap");
        // A July–June year straddling it is refused too.
        assertThatThrownBy(() -> fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "FY31/32", 7, 2031)))
                .isInstanceOf(ConflictException.class);
        // The adjacent year is fine.
        FiscalYearDto next = fiscalCalendarService.openFiscalYear(
                new OpenFiscalYearRequest(company.getUid(), "FY2032", 1, 2032));
        assertThat(next.startDate()).isEqualTo(LocalDate.of(2032, 1, 1));
        assertThat(fiscalCalendarService.listFiscalYears(company.getId())).hasSize(2);
    }

    // ---------------------------------------------------------------------------
    // ACC-01: rollover job opens the next year, idempotently, against real Postgres
    // ---------------------------------------------------------------------------

    @Test
    void rolloverJob_opensNextYear_once_andCatchesUpAfterNewYear() {
        fiscalCalendarService.seedCurrentYear(company.getId()); // FY<this year>
        int thisYear = LocalDate.now().getYear();

        // Mid-year (company time zone is Africa/Dar_es_Salaam by default): next year opened.
        rolloverJob.rollOverAllCompanies(
                java.time.Instant.parse(thisYear + "-10-10T09:00:00Z"));
        List<FiscalYearDto> afterFirst = fiscalCalendarService.listFiscalYears(company.getId());
        assertThat(afterFirst).extracting(FiscalYearDto::yearCode)
                .containsExactly("FY" + (thisYear + 1), "FY" + thisYear);
        FiscalYearDto next = afterFirst.get(0);
        assertThat(next.startDate()).isEqualTo(LocalDate.of(thisYear + 1, 1, 1));
        assertThat(fiscalCalendarService.listPeriodsForYear(next.uid())).hasSize(12)
                .allMatch(p -> p.status() == PeriodStatus.OPEN);

        // Running again the same day changes nothing.
        rolloverJob.rollOverAllCompanies(
                java.time.Instant.parse(thisYear + "-10-11T09:00:00Z"));
        assertThat(fiscalCalendarService.listFiscalYears(company.getId())).hasSize(2);

        // 2 Jan next year: the year after next is opened, still contiguous and non-overlapping.
        rolloverJob.rollOverAllCompanies(
                java.time.Instant.parse((thisYear + 1) + "-01-02T09:00:00Z"));
        List<FiscalYearDto> afterNewYear = fiscalCalendarService.listFiscalYears(company.getId());
        assertThat(afterNewYear).extracting(FiscalYearDto::yearCode)
                .containsExactly("FY" + (thisYear + 2), "FY" + (thisYear + 1), "FY" + thisYear);
        assertThat(afterNewYear.get(0).startDate())
                .isEqualTo(afterNewYear.get(1).endDate().plusDays(1));

        // The job ran under a SYSTEM principal and handed the caller's context back.
        assertThat(RequestContext.get().userId()).isEqualTo(rootId);
    }

    // ---------------------------------------------------------------------------
    // Cross-tenant isolation (BR-GL: per-company; assertCanActIn)
    // ---------------------------------------------------------------------------

    @Test
    void closePeriod_crossCompany_blocked() {
        // company A has the period
        fiscalCalendarService.seedCurrentYear(company.getId());
        FiscalPeriodDto periodA = fiscalCalendarService.listPeriods(company.getId()).get(0);

        // a different company + a non-root principal scoped to it
        Organisation org2 = organisations.save(new Organisation("Other Org"));
        Company companyB  = companies.save(new Company(org2, "FYB", "Other Co"));
        Branch branchB    = branches.save(new Branch(companyB, "FYBB1", "Other Branch"));
        AppUser userB = users.save(inOrganisation(new AppUser(
                "fyit_userb", passwordEncoder.encode("Pass1!"), "User B"), org2.getId()));

        RequestContext.set(new RequestContext.Principal(
                userB.getId(), "fyit_userb", false, companyB.getId(), branchB.getId(), null));

        assertThatThrownBy(() -> fiscalCalendarService.closePeriod(periodA.uid()))
                .isInstanceOf(ForbiddenException.class);
    }
}
