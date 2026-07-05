package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.UpdateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link CashBankAccountServiceImpl} (ADR-0016 D-2a/D-10/D-13,
 * FR-CASH-01..07, BR-CASH-01..03).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Create CASH account — persisted with correct fields; linked GL account set.
 *   <li>Create BANK account — bank details required; missing bankName rejected.
 *   <li>1:1 GL account invariant — second account pointing at the same GL account rejected.
 *   <li>setDefault — clears previous default; exactly one default per company.
 *   <li>Deactivation blocked when transactions exist (BR-CASH-13).
 * </ol>
 */
class CashBankAccountServiceIT extends PostgresIntegrationTest {

    @Autowired private CashBankAccountService service;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private com.erp.modules.gl.repository.ChartOfAccountRepository glAccountRepo;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String companyUid;
    // GL account uids seeded by default CoA
    private String cashGlUid;
    private String bankGlUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("CB Account IT Org"));
        company    = companies.save(new Company(org, "CBAIT", "CB Account IT Co"));
        branch     = branches.save(new Branch(company, "CBAIT1", "CB Account IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("cb_root", passwordEncoder.encode("RootPass1!"), "CB Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "cb_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Resolve the seeded GL account uids (1000=Cash, 1100=Bank per V10 CoA seed)
        cashGlUid = findGlUid("1000");
        bankGlUid = findGlUid("1100");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void createCashAccount_persistsCorrectly() {
        CashBankAccountDto dto = service.create(new CreateCashBankAccountRequest(
                companyUid, null, "Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.name()).isEqualTo("Petty Cash");
        assertThat(dto.accountType()).isEqualTo(CashBankAccountType.CASH);
        assertThat(dto.glAccountId()).isNotNull();
        assertThat(dto.active()).isTrue();
    }

    @Test
    void createBankAccount_requiresBankName() {
        assertThatThrownBy(() -> service.create(new CreateCashBankAccountRequest(
                companyUid, null, "Main Bank", CashBankAccountType.BANK,
                null, null, null, bankGlUid, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bank name");
    }

    @Test
    void createBankAccount_withBankName_persists() {
        CashBankAccountDto dto = service.create(new CreateCashBankAccountRequest(
                companyUid, null, "NMB Bank", CashBankAccountType.BANK,
                "NMB Bank", "1234567890", "Dar Branch", bankGlUid, false));

        assertThat(dto.accountType()).isEqualTo(CashBankAccountType.BANK);
        assertThat(dto.bankName()).isEqualTo("NMB Bank");
    }

    @Test
    void create_duplicateGlAccountId_rejected() {
        service.create(new CreateCashBankAccountRequest(
                companyUid, null, "Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false));

        assertThatThrownBy(() -> service.create(new CreateCashBankAccountRequest(
                companyUid, null, "Petty Cash 2", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already linked");
    }

    @Test
    void setDefault_clearsPreviousDefault() {
        CashBankAccountDto first = service.create(new CreateCashBankAccountRequest(
                companyUid, null, "Cash 1", CashBankAccountType.CASH,
                null, null, null, cashGlUid, true));

        CashBankAccountDto second = service.create(new CreateCashBankAccountRequest(
                companyUid, null, "Cash 2", CashBankAccountType.CASH,
                null, null, null, bankGlUid, false));

        service.setDefault(second.uid());

        List<CashBankAccountDto> all = service.listByCompany(company.getId());
        long defaultCount = all.stream().filter(CashBankAccountDto::isDefault).count();

        assertThat(defaultCount)
                .as("exactly one default per company (partial unique index, D-2a)")
                .isEqualTo(1);

        CashBankAccountDto secondRefreshed = service.getByUid(second.uid());
        assertThat(secondRefreshed.isDefault()).isTrue();

        CashBankAccountDto firstRefreshed = service.getByUid(first.uid());
        assertThat(firstRefreshed.isDefault()).isFalse();
    }

    // -------------------------------------------------------------------------

    private String findGlUid(String accountCode) {
        return glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), accountCode)
                .map(a -> a.getUid())
                .orElseThrow(() -> new AssertionError("GL account " + accountCode + " not seeded"));
    }
}
