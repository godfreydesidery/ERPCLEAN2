package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.bi.service.DashboardService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.sales.service.ProfitabilityReportQuery;
import com.erp.modules.sales.service.SalesReportQuery;
import com.erp.modules.stock.domain.dto.StockMovementReportFiltersDto;
import com.erp.modules.stock.domain.enums.StockMovementReportMode;
import com.erp.modules.stock.service.ItemInquiryQuery;
import com.erp.modules.stock.service.ProductStockReportQuery;
import com.erp.modules.stock.service.StockMovementReportQuery;
import com.erp.modules.stock.service.StockReportQuery;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Every report that takes a branch filter refuses a branch the caller is not assigned to.
 *
 * <p>Sales, Profitability, Stock and Stock Movement resolved the branch uid company-scoped only, so
 * a user assigned to one branch could read another branch's sales, margins and stock by editing the
 * URL — the {@code X-Branch-Uid} header path refuses exactly that. Product List / Stock Value and
 * Item Inquiry had the check as private copies; all six now share {@link BranchReadGuard}.
 *
 * <p>The caller here is a NON-root company member — root short-circuits the guard and would pass
 * every case regardless (the trap behind most of this codebase's RBAC regressions).
 */
class BranchReadGuardIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private UserBranchRepository   userBranches;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    @Autowired private SalesReportQuery         salesReport;
    @Autowired private ProfitabilityReportQuery profitabilityReport;
    @Autowired private StockReportQuery         stockReport;
    @Autowired private StockMovementReportQuery stockMovementReport;
    @Autowired private ProductStockReportQuery  productStockReport;
    @Autowired private ItemInquiryQuery         itemInquiry;
    @Autowired private DashboardService         dashboard;

    private Company company;
    private Branch  ownBranch;
    private Branch  otherBranch;
    private AppUser clerk;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("BranchGuard IT Org"));
        company     = companies.save(new Company(org, "BRGD", "BranchGuard IT Co"));
        ownBranch   = branches.save(new Branch(company, "BRGD1", "Own Branch"));
        otherBranch = branches.save(new Branch(company, "BRGD2", "Other Branch"));

        AppUser u = new AppUser("branchguard_clerk", passwordEncoder.encode("BGuard@1!Xx"), "Clerk");
        u.setOrganisationId(org.getId());
        clerk = users.save(u);
        testData.seedMembership(clerk.getUid(), company.getUid());
        userBranches.save(new UserBranch(clerk.getId(), ownBranch, clerk.getId()));

        RequestContext.set(new RequestContext.Principal(
                clerk.getId(), "branchguard_clerk", false, company.getId(), ownBranch.getId(), null,
                org.getId()));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void salesReport() {
        assertBranchGuarded(uid -> salesReport.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now(), null, null, null, uid));
    }

    @Test
    void profitabilityReport() {
        assertBranchGuarded(uid -> profitabilityReport.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now(), uid));
    }

    @Test
    void stockReport() {
        assertBranchGuarded(uid -> stockReport.report(company.getId(), uid));
    }

    @Test
    void stockMovementReport() {
        assertBranchGuarded(uid -> stockMovementReport.report(company.getId(),
                new StockMovementReportFiltersDto(LocalDate.now().minusDays(1), LocalDate.now(),
                        StockMovementReportMode.SUMMARY, uid, null),
                0, 20));
    }

    @Test
    void productListAndStockValue() {
        assertBranchGuarded(uid -> productStockReport.report(company.getId(), uid, null, false));
        assertBranchGuarded(uid -> productStockReport.report(company.getId(), uid, null, true));
    }

    @Test
    void itemInquiry() {
        assertBranchGuarded(uid -> itemInquiry.inquire(company.getId(), "anything", uid, false));
    }

    /** The BI dashboard takes a raw branch id rather than a uid; the same rule applies to it. */
    @Test
    void biDashboardBranchPanels() {
        LocalDate from = LocalDate.now().minusDays(1);
        LocalDate to   = LocalDate.now();
        assertThatCode(() -> dashboard.salesByBranch(company.getId(), ownBranch.getId(), from, to))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> dashboard.salesByBranch(company.getId(), otherBranch.getId(), from, to))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
        assertThatThrownBy(() -> dashboard.crmSnapshot(company.getId(), otherBranch.getId(), from, to))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> dashboard.dashboard(company.getId(), from, to, otherBranch.getId()))
                .isInstanceOf(ForbiddenException.class);
    }

    /** Own branch and no branch both read; the unassigned branch is refused with the branch wording. */
    private void assertBranchGuarded(ReportCall call) {
        assertThatCode(exec(call, ownBranch.getUid())).doesNotThrowAnyException();
        assertThatCode(exec(call, null)).doesNotThrowAnyException();
        assertThatThrownBy(exec(call, otherBranch.getUid()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    private static ThrowingCallable exec(ReportCall call, String branchUid) {
        return () -> call.run(branchUid);
    }

    @FunctionalInterface
    private interface ReportCall {
        void run(String branchUid) throws Throwable;
    }
}
