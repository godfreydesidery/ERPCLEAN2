package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.dto.AccountDto;
import com.erp.modules.gl.domain.dto.CreateAccountRequest;
import com.erp.modules.gl.domain.dto.UpdateAccountRequest;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for ChartOfAccountService invariants (ADR-0013 D-2a, FR-GL-01..05,
 * BR-GL-07, NFR-GL-01).
 *
 * <p>Tests cover:
 * <ul>
 *   <li>seedDefaults produces the expected TZ CoA accounts with correct types + normal balances.</li>
 *   <li>create a new account; deactivate it.</li>
 *   <li>BR-GL-07: delete rejected after posting; deactivate allowed.</li>
 *   <li>FR-GL-01: duplicate account code per company rejected (ConflictException).</li>
 *   <li>NFR-GL-01: cross-tenant read blocked (getByUid for company B account from company A context).</li>
 * </ul>
 */
class ChartOfAccountServiceIT extends PostgresIntegrationTest {

    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private GLPostingService postingService;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("CoA IT Org"));
        company = companies.save(new Company(org, "COAIT", "CoA IT Co"));
        branch  = branches.save(new Branch(company, "COAB1", "CoA IT Branch"));

        AppUser root = new AppUser("coa_root", passwordEncoder.encode("RootPass1!"), "CoA Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "coa_root", true, company.getId(), branch.getId(), null));

        // Seed GL foundations (mirror SalesPostingHandlerIT setUp exactly)
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // seedDefaults: expected TZ CoA accounts present with correct type + normal balance
    // =========================================================================

    @Test
    void seedDefaults_producesExpectedAccounts_withCorrectTypeAndNormalBalance() {
        Page<AccountDto> page = chartOfAccountService.list(company.getId(), Pageable.unpaged());
        List<AccountDto> all  = page.getContent();

        // Must contain the floor required by gl.md §3.1 + ADR-0013 D-15
        assertAccount(all, "1000", AccountType.ASSET,     NormalBalance.DEBIT,  "Cash");
        assertAccount(all, "1100", AccountType.ASSET,     NormalBalance.DEBIT,  "Bank");
        assertAccount(all, "1200", AccountType.ASSET,     NormalBalance.DEBIT,  "Accounts Receivable");
        assertAccount(all, "1300", AccountType.ASSET,     NormalBalance.DEBIT,  "Inventory");
        assertAccount(all, "2100", AccountType.LIABILITY, NormalBalance.CREDIT, "Accounts Payable");
        assertAccount(all, "2200", AccountType.LIABILITY, NormalBalance.CREDIT, "VAT Payable");
        assertAccount(all, "3000", AccountType.EQUITY,    NormalBalance.CREDIT, null);
        assertAccount(all, "3900", AccountType.EQUITY,    NormalBalance.CREDIT, "Retained Earnings");
        assertAccount(all, "4100", AccountType.INCOME,    NormalBalance.CREDIT, "Sales Revenue");
        assertAccount(all, "5100", AccountType.EXPENSE,   NormalBalance.DEBIT,  "Cost of Goods Sold");
    }

    @Test
    void seedDefaults_isIdempotent_secondCallDoesNotDuplicate() {
        long beforeCount = accountRepo.findByCompanyId(company.getId(), Pageable.unpaged())
                .getTotalElements();

        // Seed again — must not insert duplicates
        chartOfAccountService.seedDefaults(company.getId());

        long afterCount = accountRepo.findByCompanyId(company.getId(), Pageable.unpaged())
                .getTotalElements();
        assertThat(afterCount).isEqualTo(beforeCount);
    }

    // =========================================================================
    // create + deactivate
    // =========================================================================

    @Test
    void create_newAccount_persistedAndReturned() {
        AccountDto dto = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6100", "Marketing Expense", AccountType.EXPENSE));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.accountCode()).isEqualTo("6100");
        assertThat(dto.accountType()).isEqualTo(AccountType.EXPENSE);
        assertThat(dto.normalBalance()).isEqualTo(NormalBalance.DEBIT);
        assertThat(dto.active()).isTrue();

        // Round-trip from DB
        AccountDto fetched = chartOfAccountService.getByUid(dto.uid());
        assertThat(fetched.accountCode()).isEqualTo("6100");
    }

    @Test
    void deactivate_setsIsActiveFalse_accountStillExists() {
        AccountDto created = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6200", "Depreciation", AccountType.EXPENSE));

        AccountDto deactivated = chartOfAccountService.deactivate(created.uid());

        assertThat(deactivated.active()).isFalse();
        // Account still visible (deactivated ≠ deleted — FR-GL-03)
        AccountDto still = chartOfAccountService.getByUid(created.uid());
        assertThat(still.active()).isFalse();
    }

    @Test
    void update_changesNameAndType() {
        AccountDto created = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6300", "Old Name", AccountType.EXPENSE));

        AccountDto updated = chartOfAccountService.update(
                created.uid(), new UpdateAccountRequest("New Name", AccountType.EXPENSE, null, null, null,
                        null, null, null));

        assertThat(updated.name()).isEqualTo("New Name");
        assertThat(updated.normalBalance()).isEqualTo(NormalBalance.DEBIT);
    }

    // =========================================================================
    // BR-GL-07: delete rejected after posting; deactivate allowed
    // =========================================================================

    @Test
    void delete_accountWithNoPostings_succeeds() {
        AccountDto toDelete = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6400", "Temp Account", AccountType.EXPENSE));

        // No postings — delete must succeed (BR-GL-07 only blocks when postings exist)
        chartOfAccountService.delete(toDelete.uid());

        assertThatThrownBy(() -> chartOfAccountService.getByUid(toDelete.uid()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_accountWithPostings_rejected_deactivateAllowed() {
        // Post a balanced entry to an account we then try to delete (BR-GL-07)
        Long cashId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(ChartOfAccount::getId).orElseThrow();
        Long cogsId = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "5100")
                .map(ChartOfAccount::getId).orElseThrow();

        postingService.post(new com.erp.modules.gl.domain.dto.JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Post to cogs", JournalSourceType.MANUAL, null, null, rootId,
                List.of(
                        new com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft(
                                cogsId, new BigDecimal("100"), null, "TZS", null),
                        new com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft(
                                cashId, null, new BigDecimal("100"), "TZS", null)
                )
        ));

        // Delete must be rejected (BR-GL-07)
        ChartOfAccount cogsAccount = accountRepo.findByCompanyIdAndAccountCode(
                company.getId(), "5100").orElseThrow();
        assertThatThrownBy(() -> chartOfAccountService.delete(cogsAccount.getUid()))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("BR-GL-07");

        // Deactivate must succeed (BR-GL-07: "deactivate it instead")
        AccountDto deactivated = chartOfAccountService.deactivate(cogsAccount.getUid());
        assertThat(deactivated.active()).isFalse();
    }

    // =========================================================================
    // FR-GL-01: duplicate account code per company rejected
    // =========================================================================

    @Test
    void create_duplicateCodeSameCompany_rejected() {
        chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6500", "Account A", AccountType.EXPENSE));

        assertThatThrownBy(() -> chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6500", "Account A duplicate", AccountType.EXPENSE)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_sameCodeDifferentCompanies_allowed() {
        // Code "6500" in company 1
        chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6500", "Account A", AccountType.EXPENSE));

        // Set up company 2
        Organisation org2   = organisations.save(new Organisation("CoA IT Org 2"));
        Company company2    = companies.save(new Company(org2, "COAIT2", "CoA IT Co 2"));
        Branch  branch2     = branches.save(new Branch(company2, "COAB2", "CoA IT Branch 2"));
        RequestContext.set(new RequestContext.Principal(
                rootId, "coa_root", true, company2.getId(), branch2.getId(), null));
        chartOfAccountService.seedDefaults(company2.getId());
        fiscalCalendarService.seedCurrentYear(company2.getId());
        glConfigService.seedDefaults(company2.getId());

        // Same code "6500" in company 2 — must succeed (code unique per company, not globally)
        AccountDto dto2 = chartOfAccountService.create(new CreateAccountRequest(
                company2.getUid(), "6500", "Account B", AccountType.EXPENSE));
        assertThat(dto2.uid()).isNotBlank();
    }

    // =========================================================================
    // NFR-GL-01: cross-tenant read blocked
    // =========================================================================

    @Test
    void getByUid_crossTenantRead_blocked() {
        // Account belonging to company
        AccountDto coA1 = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "6600", "Company1 Account", AccountType.EXPENSE));

        // Create company 2; switch to a NON-ROOT principal scoped to it. Cross-tenant DENIAL must
        // be asserted with a non-root user: root legitimately BYPASSES tenant scope (it is not
        // blocked). A root-based version was a false pass — it only "threw" because the ROOT_BYPASS
        // audit write failed inside getByUid's read-only TX (ISSUES-REGISTER #11, now fixed), not
        // because of isolation. Non-root is denied cleanly at ScopeGuard before any audit.
        Organisation org2 = organisations.save(new Organisation("CoA Tenant Org 2"));
        Company company2  = companies.save(new Company(org2, "CATEN2", "CoA Tenant Co 2"));
        Branch  branch2   = branches.save(new Branch(company2, "CAT2B", "CoA Tenant Branch 2"));
        AppUser nonRoot = users.save(new AppUser(
                "coa_nonroot", passwordEncoder.encode("Pass1!"), "CoA NonRoot"));
        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "coa_nonroot", false, company2.getId(), branch2.getId(), null));

        // Attempt to read company1's account from a non-root company2 context — must be blocked
        String coA1Uid = coA1.uid();
        assertThatThrownBy(() -> chartOfAccountService.getByUid(coA1Uid))
                .as("assertCanActIn must block cross-tenant CoA read (NFR-GL-01)")
                .isInstanceOf(ForbiddenException.class);
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private static void assertAccount(List<AccountDto> accounts, String code,
                                      AccountType expectedType, NormalBalance expectedNormal,
                                      String expectedNameFragment) {
        AccountDto found = accounts.stream()
                .filter(a -> a.accountCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Account " + code + " not found in seeded CoA"));
        assertThat(found.accountType()).as("Account %s type", code).isEqualTo(expectedType);
        assertThat(found.normalBalance()).as("Account %s normalBalance", code).isEqualTo(expectedNormal);
        assertThat(found.active()).as("Account %s active", code).isTrue();
        if (expectedNameFragment != null) {
            assertThat(found.name()).as("Account %s name", code).contains(expectedNameFragment);
        }
    }
}
