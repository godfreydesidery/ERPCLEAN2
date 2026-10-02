package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.domain.dto.PayrollRunStatutoryReportDto;
import com.erp.modules.hr.domain.dto.PayrollStatutoryPeriodReportDto;
import com.erp.modules.hr.domain.dto.StatutoryLineDto;
import com.erp.modules.hr.domain.dto.StatutorySummaryDto;
import com.erp.modules.hr.domain.enums.ContractType;
import com.erp.modules.hr.domain.enums.PayrollRunStatus;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Payroll Statutory Summary (FR-HR-23) against real Postgres: the per-run summary must equal the
 * run's own lines to the cent, carry the employee's statutory numbers, and the period view must
 * count only final (APPROVED / POSTED / PAID) runs — telling the reader about the rest.
 */
class PayrollStatutoryReportQueryIT extends PostgresIntegrationTest {

    @Autowired private PayrollStatutoryReportQuery query;
    @Autowired private PayrollRunService           payrollRunService;
    @Autowired private EmployeeService             employeeService;
    @Autowired private ContractServiceImpl         contractService;
    @Autowired private HrStatutorySeeder           statutorySeeder;
    @Autowired private HrGlSeeder                  hrGlSeeder;
    @Autowired private ChartOfAccountService       chartOfAccountService;
    @Autowired private FiscalCalendarService       fiscalCalendarService;
    @Autowired private GlConfigService             glConfigService;
    @Autowired private OrganisationRepository      organisations;
    @Autowired private CompanyRepository           companies;
    @Autowired private BranchRepository            branches;
    @Autowired private AppUserRepository           users;
    @Autowired private PasswordEncoder             passwordEncoder;
    @Autowired private IamTestData                 testData;

    private Organisation org;
    private Company company;
    private Branch  branch;
    private Long    rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org     = organisations.save(new Organisation("PayStat IT Org"));
        company = companies.save(new Company(org, "PSTAT", "PayStat IT Co"));
        branch  = branches.save(new Branch(company, "PSTAT1", "PayStat IT Branch"));

        AppUser root = new AppUser("pstat_root", passwordEncoder.encode("RootPass1!"), "PayStat Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        asRoot();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        hrGlSeeder.seedDefaults(company.getId());
        statutorySeeder.seedDefaults(company.getId());

        hire("Asha", "Mrema", "TIN-111", "NSSF-111", new BigDecimal("1200000"));
        hire("Baraka", "Juma", null, "NSSF-222", new BigDecimal("650000"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void perRunSummary_equalsTheRunsOwnLines_andCarriesStatutoryNumbers() {
        PayrollRunDto run = createRun(6, LocalDate.of(2026, 6, 30));
        payrollRunService.calculate(run.uid());

        PayrollRunStatutoryReportDto calculated = query.forRun(run.uid());
        assertThat(calculated.provisional())
                .as("a CALCULATED run can still change — the summary must say it is provisional")
                .isTrue();

        PayrollRunDto approved = payrollRunService.approve(run.uid());
        PayrollRunStatutoryReportDto dto = query.forRun(run.uid());
        StatutorySummaryDto s = dto.summary();

        assertThat(dto.provisional()).isFalse();
        assertThat(dto.companyId()).isEqualTo(company.getId());
        assertThat(s.status()).isEqualTo(PayrollRunStatus.APPROVED);
        assertThat(s.employeeCount()).isEqualTo(2);
        assertThat(dto.lines()).hasSize(2);

        // Independently from the run's own /lines.
        List<PayrollLineDto> lines = payrollRunService.listLines(run.uid());
        assertThat(s.grossTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::grossAmount));
        assertThat(s.payeTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::payeAmount));
        assertThat(s.nssfEmployeeTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::nssfEmployeeAmount));
        assertThat(s.nssfEmployerTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::nssfEmployerAmount));
        assertThat(s.wcfTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::wcfEmployerAmount));
        assertThat(s.sdlTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::sdlEmployerAmount));
        assertThat(s.heslbTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::heslbAmount));
        assertThat(s.netTotal()).isEqualByComparingTo(sum(lines, PayrollLineDto::netAmount));
        // And with the run header the GL posting is built from.
        assertThat(s.grossTotal()).isEqualByComparingTo(approved.grossTotal());
        assertThat(s.netTotal()).isEqualByComparingTo(approved.netTotal());
        assertThat(s.employerCostTotal())
                .isEqualByComparingTo(s.nssfEmployerTotal().add(s.wcfTotal()).add(s.sdlTotal()));
        assertThat(s.payeTotal()).as("a 1.2m salary is above the PAYE threshold").isPositive();

        StatutoryLineDto asha = dto.lines().stream()
                .filter(l -> "NSSF-111".equals(l.nssfNumber())).findFirst().orElseThrow();
        assertThat(asha.tin()).isEqualTo("TIN-111");
        assertThat(asha.employeeName()).contains("Asha");
        StatutoryLineDto baraka = dto.lines().stream()
                .filter(l -> "NSSF-222".equals(l.nssfNumber())).findFirst().orElseThrow();
        assertThat(baraka.tin()).as("a TIN never captured stays blank, not invented").isNull();
    }

    @Test
    void periodReport_countsOnlyFinalRuns_andCountsTheOthers() {
        // June: approved + posted → counted.
        PayrollRunDto june = createRun(6, LocalDate.of(2026, 6, 30));
        payrollRunService.calculate(june.uid());
        payrollRunService.approve(june.uid());
        payrollRunService.post(june.uid());
        // July: calculated only → pending, not counted.
        PayrollRunDto july = createRun(7, LocalDate.of(2026, 7, 31));
        payrollRunService.calculate(july.uid());
        // August: posted then reversed → reversed, not counted.
        PayrollRunDto aug = createRun(8, LocalDate.of(2026, 8, 31));
        payrollRunService.calculate(aug.uid());
        payrollRunService.approve(aug.uid());
        payrollRunService.post(aug.uid());
        payrollRunService.reverse(aug.uid());
        // September: approved but outside the window.
        PayrollRunDto sep = createRun(9, LocalDate.of(2026, 9, 30));
        payrollRunService.calculate(sep.uid());
        payrollRunService.approve(sep.uid());

        PayrollStatutoryPeriodReportDto dto = query.forPeriod(company.getId(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 8, 31));

        assertThat(dto.runs()).extracting(StatutorySummaryDto::runUid).containsExactly(june.uid());
        assertThat(dto.pendingRunCount()).isEqualTo(1);
        assertThat(dto.reversedRunCount()).isEqualTo(1);
        assertThat(dto.totals().runCount()).isEqualTo(1);
        assertThat(dto.totals().payslipCount()).isEqualTo(2);

        StatutorySummaryDto perRun = query.forRun(june.uid()).summary();
        assertThat(dto.totals().grossTotal()).isEqualByComparingTo(perRun.grossTotal());
        assertThat(dto.totals().payeTotal()).isEqualByComparingTo(perRun.payeTotal());
        assertThat(dto.totals().nssfEmployerTotal()).isEqualByComparingTo(perRun.nssfEmployerTotal());
        assertThat(dto.totals().sdlTotal()).isEqualByComparingTo(perRun.sdlTotal());
        assertThat(dto.totals().employerCostTotal()).isEqualByComparingTo(perRun.employerCostTotal());
    }

    @Test
    void periodReport_rejectsAnInvertedRange() {
        assertThatThrownBy(() -> query.forPeriod(company.getId(),
                LocalDate.of(2026, 7, 1), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRun_isNotFound() {
        assertThatThrownBy(() -> query.forRun("NOSUCHRUNUID00000000000000"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNonRootUserOfAnotherCompany_isRefused() {
        PayrollRunDto run = createRun(6, LocalDate.of(2026, 6, 30));
        payrollRunService.calculate(run.uid());

        Company other = companies.save(new Company(org, "PSTOT", "PayStat Other Co"));
        Branch otherBranch = branches.save(new Branch(other, "PSTOT1", "Other Branch"));
        AppUser outsider = new AppUser("pstat_outsider", passwordEncoder.encode("Outsider1!x"), "Outsider");
        outsider.setOrganisationId(org.getId());
        outsider = users.save(outsider);
        testData.seedMembership(outsider.getUid(), other.getUid());
        RequestContext.set(new RequestContext.Principal(outsider.getId(), "pstat_outsider", false,
                other.getId(), otherBranch.getId(), null, org.getId()));

        assertThatThrownBy(() -> query.forRun(run.uid())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> query.forPeriod(company.getId(),
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .isInstanceOf(ForbiddenException.class);
    }

    // -------------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "pstat_root", true, company.getId(), branch.getId(), null));
    }

    private void hire(String first, String last, String tin, String nssf, BigDecimal basic) {
        String uid = employeeService.create(new CreateEmployeeRequest(
                first, last, null, tin, nssf, null,
                LocalDate.of(1990, 1, 1), null,
                LocalDate.of(2024, 1, 1),
                null, "Clerk", branch.getId(), null,
                null, null, null, null, null, null,
                null, null, null, null, null, null)).uid();
        contractService.createForEmployee(uid, new CreateContractRequest(
                ContractType.PERMANENT, basic,
                LocalDate.of(2024, 1, 1), null,
                true, true, false, true, true));
    }

    private PayrollRunDto createRun(int month, LocalDate payDate) {
        return payrollRunService.create(new CreatePayrollRunRequest(
                (short) month, (short) 2026, payDate, branch.getId()));
    }

    private static BigDecimal sum(List<PayrollLineDto> lines, Function<PayrollLineDto, BigDecimal> f) {
        return lines.stream().map(f).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
