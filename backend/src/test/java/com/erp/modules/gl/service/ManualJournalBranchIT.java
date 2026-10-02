package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.PostJournalLineRequest;
import com.erp.modules.gl.domain.dto.PostJournalRequest;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Live-test defect 2: a manual journal could not carry a branch, so every one landed in
 * "Company-level entries". The request now takes an optional {@code branchUid}, resolved inside
 * the company and checked against the caller's branch assignments; no uid stays company level.
 *
 * <p>The caller is a NON-root accountant assigned to one branch — root would short-circuit the
 * assignment check and hide the case that matters.
 */
class ManualJournalBranchIT extends PostgresIntegrationTest {

    @Autowired private JournalService            journalService;
    @Autowired private ChartOfAccountService     chartOfAccountService;
    @Autowired private FiscalCalendarService     fiscalCalendarService;
    @Autowired private GlConfigService           glConfigService;
    @Autowired private ChartOfAccountRepository  accounts;
    @Autowired private JournalEntryRepository    entries;
    @Autowired private JournalLineRepository     lines;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private UserBranchRepository      userBranches;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private IamTestData               testData;

    private Company company;
    private Branch  ownBranch;
    private Branch  otherBranch;
    private Branch  foreignBranch;
    private LocalDate postingDate;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("MJ Branch Org"));
        company     = companies.save(new Company(org, "MJBR", "MJ Branch Co"));
        ownBranch   = branches.save(new Branch(company, "MJ1", "Dar"));
        otherBranch = branches.save(new Branch(company, "MJ2", "Arusha"));
        Company other = companies.save(new Company(org, "MJBX", "Other Co"));
        foreignBranch = branches.save(new Branch(other, "MJX", "Foreign"));

        AppUser u = new AppUser("mj_accountant", passwordEncoder.encode("Accountant@1X"), "Acct");
        u.setOrganisationId(org.getId());
        AppUser accountant = users.save(u);
        testData.seedMembership(accountant.getUid(), company.getUid());
        userBranches.save(new UserBranch(accountant.getId(), ownBranch, accountant.getId()));

        // Session branch = the accountant's own branch (as after login).
        RequestContext.set(new RequestContext.Principal(
                accountant.getId(), "mj_accountant", false, company.getId(), ownBranch.getId(), null,
                org.getId()));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        postingDate = fiscalCalendarService.listPeriods(company.getId()).get(0).startDate();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void branchUid_stampsTheEntryAndEveryLine() {
        JournalEntryDto posted = journalService.postManual(request(ownBranch.getUid()));

        JournalEntry entry = entries.findByUid(posted.uid()).orElseThrow();
        assertThat(entry.getBranchId()).isEqualTo(ownBranch.getId());
        assertThat(lines.findByEntryIdOrderByLineNo(entry.getId()))
                .hasSize(2)
                .allSatisfy(l -> assertThat(l.getBranchId()).isEqualTo(ownBranch.getId()));
    }

    @Test
    void noBranchUid_staysCompanyLevel_evenThoughTheSessionHasABranch() {
        JournalEntryDto posted = journalService.postManual(request(null));

        JournalEntry entry = entries.findByUid(posted.uid()).orElseThrow();
        assertThat(entry.getBranchId()).isNull();
        assertThat(lines.findByEntryIdOrderByLineNo(entry.getId()))
                .allSatisfy(l -> assertThat(l.getBranchId()).isNull());
    }

    @Test
    void unassignedBranch_isRefused_forANonRootCaller() {
        long before = entries.count();
        assertThatThrownBy(() -> journalService.postManual(request(otherBranch.getUid())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not assigned to that branch")
                .hasMessageNotContaining(otherBranch.getUid());
        assertThat(entries.count()).isEqualTo(before);
    }

    @Test
    void anotherCompanysBranch_isNotFound_neverASilentCompanyLevelPost() {
        long before = entries.count();
        assertThatThrownBy(() -> journalService.postManual(request(foreignBranch.getUid())))
                .isInstanceOf(NotFoundException.class)
                .hasMessageNotContaining(foreignBranch.getUid());
        assertThat(entries.count()).isEqualTo(before);
    }

    private PostJournalRequest request(String branchUid) {
        String rent   = accounts.findByCompanyIdAndAccountCode(company.getId(), "5200").orElseThrow().getUid();
        String equity = accounts.findByCompanyIdAndAccountCode(company.getId(), "3000").orElseThrow().getUid();
        return new PostJournalRequest(
                company.getUid(), postingDate, "Branch rent accrual", JournalSourceType.MANUAL, null,
                List.of(
                        new PostJournalLineRequest(rent, new BigDecimal("150000"), BigDecimal.ZERO, "rent"),
                        new PostJournalLineRequest(equity, BigDecimal.ZERO, new BigDecimal("150000"), "owner")),
                branchUid);
    }
}
