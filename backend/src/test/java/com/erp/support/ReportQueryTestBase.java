package com.erp.support;

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
import com.erp.platform.security.RequestContext;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Shared scaffold for the report-query integration tests: one company with two branches, a root
 * user, and a NON-root clerk who is a company member assigned to the first branch only — the
 * caller every branch-filter refusal has to be proven against (root short-circuits the guard).
 *
 * <p>The company's time zone is pinned so "N days ago at hour H" means the same local day in the
 * test as in the query.
 */
public abstract class ReportQueryTestBase extends PostgresIntegrationTest {

    protected static final ZoneId ZONE = ZoneId.of("Africa/Dar_es_Salaam");

    @Autowired protected OrganisationRepository organisations;
    @Autowired protected CompanyRepository      companies;
    @Autowired protected BranchRepository       branches;
    @Autowired protected AppUserRepository      users;
    @Autowired protected UserBranchRepository   userBranches;
    @Autowired protected PasswordEncoder        passwordEncoder;
    @Autowired protected IamTestData            testData;
    @Autowired protected JdbcTemplate           jdbc;

    protected ReportSeed seed;
    protected Organisation org;
    protected Company company;
    protected Branch  ownBranch;
    protected Branch  otherBranch;
    protected AppUser root;
    protected AppUser clerk;

    @BeforeEach
    void setUpReportCompany() {
        testData.clearAll();
        seed = new ReportSeed(jdbc);

        org         = organisations.save(new Organisation("Report IT Org"));
        company     = companies.save(new Company(org, "RPIT", "Report IT Co"));
        ownBranch   = branches.save(new Branch(company, "RPIT1", "Own Branch"));
        otherBranch = branches.save(new Branch(company, "RPIT2", "Other Branch"));
        jdbc.update("UPDATE companies SET time_zone = ?, base_currency = 'TZS' WHERE id = ?",
                ZONE.getId(), company.getId());

        AppUser r = new AppUser("report_root", passwordEncoder.encode("RptRoot@1!Xx"), "Report Root");
        r.setRoot(true);
        r.setOrganisationId(org.getId());
        root = users.save(r);

        AppUser c = new AppUser("report_clerk", passwordEncoder.encode("RptClerk@1!Xx"), "Report Clerk");
        c.setOrganisationId(org.getId());
        clerk = users.save(c);
        testData.seedMembership(clerk.getUid(), company.getUid());
        userBranches.save(new UserBranch(clerk.getId(), ownBranch, clerk.getId()));

        asRoot();
    }

    @AfterEach
    void clearReportContext() {
        RequestContext.clear();
    }

    protected void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "report_root", true, company.getId(), ownBranch.getId(), null,
                org.getId()));
    }

    protected void asClerk() {
        RequestContext.set(new RequestContext.Principal(
                clerk.getId(), "report_clerk", false, company.getId(), ownBranch.getId(), null,
                org.getId()));
    }

    protected static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** {@code daysAgo} days before today, at {@code hour}:00 company time. */
    protected static OffsetDateTime at(int daysAgo, int hour) {
        return today().minusDays(daysAgo).atTime(hour, 0).atZone(ZONE).toOffsetDateTime();
    }
}
