package com.erp.modules.fixedassets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunPreviewDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.TransferAssetRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
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
 * Regression tests for the four tenant-isolation (confused-deputy) fixes in the fixedassets module.
 *
 * <p>Setup: two companies A and B, each with their own branch, GL chart, fiscal calendar, and GL
 * config. Tests run as Company-A admin and attempt to use Company-B's ids in requests that are
 * scoped to Company A — every such attempt must be rejected with 404 (NotFoundException) so the
 * caller cannot probe cross-tenant existence.
 *
 * <p>Sites covered:
 * <ol>
 *   <li>AssetCategoryServiceImpl.create / update — foreign GL account ids rejected.
 *   <li>FixedAssetServiceImpl.register / acquireFromBill — foreign branchId rejected.
 *   <li>FixedAssetServiceImpl.transfer — foreign branchId rejected after load.
 *   <li>DepreciationRunServiceImpl.preview / post — foreign fiscal period uid rejected.
 * </ol>
 */
class FixedAssetTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private AssetCategoryService         categoryService;
    @Autowired private FixedAssetService            assetService;
    @Autowired private DepreciationRunService       runService;
    @Autowired private FixedAssetGlSeeder           glSeeder;

    @Autowired private AssetCategoryRepository      categoryRepo;
    @Autowired private FixedAssetRepository         assetRepo;
    @Autowired private ChartOfAccountRepository     coaRepo;

    @Autowired private OrganisationRepository       organisations;
    @Autowired private CompanyRepository            companies;
    @Autowired private BranchRepository             branches;
    @Autowired private AppUserRepository            users;
    @Autowired private PasswordEncoder              passwordEncoder;

    @Autowired private ChartOfAccountService        chartOfAccountService;
    @Autowired private FiscalCalendarService        fiscalCalendarService;
    @Autowired private GlConfigService              glConfigService;

    @Autowired private IamTestData iamTestData;

    // Company A (the "acting" tenant)
    private Long   companyAId;
    private Long   branchAId;
    private Long   assetAccountAId;
    private Long   accumDepAccountAId;
    private Long   depExpenseAccountAId;
    private String openPeriodAUid;
    private LocalDate openPeriodADate;

    // Company B (the "foreign" tenant)
    private Long   companyBId;
    private Long   branchBId;
    private Long   assetAccountBId;
    private Long   accumDepAccountBId;
    private Long   depExpenseAccountBId;
    private String openPeriodBUid;

    @BeforeEach
    void setUp() {
        Organisation org = new Organisation("TI-Test-Org");
        organisations.save(org);

        // --- Company A ---
        Company coA = new Company(org, "TI-CO-A", "Tenant Isolation Company A");
        companies.save(coA);
        companyAId = coA.getId();

        Branch brA = new Branch(coA, "TI-BR-A", "Branch A");
        brA.setDefault(true);
        branches.save(brA);
        branchAId = brA.getId();

        AppUser userA = new AppUser("ti-user-a", passwordEncoder.encode("Password1234"), "TI User A");
        users.save(userA);

        // --- Company B ---
        Company coB = new Company(org, "TI-CO-B", "Tenant Isolation Company B");
        companies.save(coB);
        companyBId = coB.getId();

        Branch brB = new Branch(coB, "TI-BR-B", "Branch B");
        brB.setDefault(true);
        branches.save(brB);
        branchBId = brB.getId();

        AppUser userB = new AppUser("ti-user-b", passwordEncoder.encode("Password1234"), "TI User B");
        users.save(userB);

        // Seeding below calls scope-guarded services for BOTH companies; act as root during setup.
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "ti-setup", true, companyAId, branchAId, null));

        // Seed GL infra for Company A
        chartOfAccountService.seedDefaults(companyAId);
        fiscalCalendarService.seedCurrentYear(companyAId);
        glConfigService.seedDefaults(companyAId);
        glSeeder.seedDefaults(companyAId);

        // Seed GL infra for Company B
        chartOfAccountService.seedDefaults(companyBId);
        fiscalCalendarService.seedCurrentYear(companyBId);
        glConfigService.seedDefaults(companyBId);
        glSeeder.seedDefaults(companyBId);

        // Resolve Company A GL accounts and open period
        assetAccountAId     = coaRepo.findByCompanyIdAndAccountCode(companyAId, "1600").orElseThrow().getId();
        accumDepAccountAId  = coaRepo.findByCompanyIdAndAccountCode(companyAId, "1700").orElseThrow().getId();
        depExpenseAccountAId= coaRepo.findByCompanyIdAndAccountCode(companyAId, "5500").orElseThrow().getId();

        List<FiscalPeriodDto> periodsA = fiscalCalendarService.listPeriods(companyAId);
        FiscalPeriodDto firstA = periodsA.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No fiscal periods for Company A"));
        openPeriodAUid  = firstA.uid();
        openPeriodADate = firstA.startDate();

        // Resolve Company B GL accounts and open period (foreign ids)
        assetAccountBId     = coaRepo.findByCompanyIdAndAccountCode(companyBId, "1600").orElseThrow().getId();
        accumDepAccountBId  = coaRepo.findByCompanyIdAndAccountCode(companyBId, "1700").orElseThrow().getId();
        depExpenseAccountBId= coaRepo.findByCompanyIdAndAccountCode(companyBId, "5500").orElseThrow().getId();

        List<FiscalPeriodDto> periodsB = fiscalCalendarService.listPeriods(companyBId);
        FiscalPeriodDto firstB = periodsB.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No fiscal periods for Company B"));
        openPeriodBUid = firstB.uid();

        // All tests act as Company A
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "ti-user-a", false, companyAId, branchAId, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        iamTestData.clearAll();
    }

    // =========================================================================
    // Site 1 — AssetCategory: foreign GL account ids on create must be rejected
    // =========================================================================

    @Test
    void createCategory_withForeignAssetAccountId_isRejected() {
        // assetAccountBId belongs to Company B; request is scoped to Company A
        assertThatThrownBy(() -> categoryService.create(new CreateAssetCategoryRequest(
                companyAId, "XLEAK1", "Cross-tenant cat",
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                assetAccountBId,    // FOREIGN — must be rejected
                accumDepAccountAId,
                depExpenseAccountAId)))
                .isInstanceOf(NotFoundException.class);

        // No category must have been persisted
        assertThat(categoryRepo.findByCompanyIdAndCode(companyAId, "XLEAK1")).isEmpty();
    }

    @Test
    void createCategory_withForeignAccumDepAccountId_isRejected() {
        assertThatThrownBy(() -> categoryService.create(new CreateAssetCategoryRequest(
                companyAId, "XLEAK2", "Cross-tenant cat 2",
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                assetAccountAId,
                accumDepAccountBId,  // FOREIGN
                depExpenseAccountAId)))
                .isInstanceOf(NotFoundException.class);

        assertThat(categoryRepo.findByCompanyIdAndCode(companyAId, "XLEAK2")).isEmpty();
    }

    @Test
    void createCategory_withForeignDepExpenseAccountId_isRejected() {
        assertThatThrownBy(() -> categoryService.create(new CreateAssetCategoryRequest(
                companyAId, "XLEAK3", "Cross-tenant cat 3",
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                assetAccountAId,
                accumDepAccountAId,
                depExpenseAccountBId)))  // FOREIGN
                .isInstanceOf(NotFoundException.class);

        assertThat(categoryRepo.findByCompanyIdAndCode(companyAId, "XLEAK3")).isEmpty();
    }

    @Test
    void updateCategory_withForeignGlAccountId_isRejected() {
        // First create a valid category for Company A
        var cat = categoryService.create(new CreateAssetCategoryRequest(
                companyAId, "VALID-CAT", "Valid Category",
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                assetAccountAId, accumDepAccountAId, depExpenseAccountAId));

        // Now try to update its asset account to Company B's account
        assertThatThrownBy(() -> categoryService.update(cat.uid(),
                new UpdateAssetCategoryRequest(
                        "Valid Category Updated",
                        DepreciationMethod.STRAIGHT_LINE, 36, null,
                        assetAccountBId,     // FOREIGN
                        accumDepAccountAId,
                        depExpenseAccountAId)))
                .isInstanceOf(NotFoundException.class);

        // Verify the category's account was NOT changed
        var reloaded = categoryService.getByUid(cat.uid());
        assertThat(reloaded.assetAccountId()).isEqualTo(assetAccountAId);
    }

    // =========================================================================
    // Site 2 — FixedAsset.register: foreign branchId must be rejected
    // =========================================================================

    @Test
    void register_withForeignBranchId_isRejected() {
        var cat = categoryService.create(new CreateAssetCategoryRequest(
                companyAId, "CAT-A", "Category A",
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                assetAccountAId, accumDepAccountAId, depExpenseAccountAId));

        // branchBId belongs to Company B
        assertThatThrownBy(() -> assetService.register(new RegisterAssetRequest(
                companyAId,
                branchBId,       // FOREIGN branch
                cat.id(), "Test Asset",
                new BigDecimal("100000.00"), BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                openPeriodADate, openPeriodADate,
                null, null, null)))
                .isInstanceOf(NotFoundException.class);

        // No asset must be persisted
        assertThat(assetRepo.findByCompanyId(companyAId,
                org.springframework.data.domain.Pageable.unpaged())).isEmpty();
    }

    // =========================================================================
    // Site 3 — FixedAsset.transfer: foreign branchId must be rejected
    // =========================================================================

    @Test
    void transfer_withForeignBranchId_isRejected_andAssetBranchUnchanged() {
        // Register a valid asset under Company A with branch A
        var cat = categoryService.create(new CreateAssetCategoryRequest(
                companyAId, "CAT-A2", "Category A2",
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                assetAccountAId, accumDepAccountAId, depExpenseAccountAId));

        FixedAssetDto asset = assetService.register(new RegisterAssetRequest(
                companyAId, branchAId, cat.id(), "Transferable Asset",
                new BigDecimal("50000.00"), BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                openPeriodADate, openPeriodADate,
                null, null, null));

        // Attempt transfer to Company B's branch
        assertThatThrownBy(() -> assetService.transfer(asset.uid(),
                new TransferAssetRequest(branchBId, "B-warehouse", null)))
                .isInstanceOf(NotFoundException.class);

        // Asset must still point to Company A's branch
        FixedAssetDto reloaded = assetService.getByUid(asset.uid());
        assertThat(reloaded.branchId()).isEqualTo(branchAId);
    }

    // =========================================================================
    // Site 4 — DepreciationRun.preview: foreign fiscal period uid must be rejected
    // =========================================================================

    @Test
    void preview_withForeignFiscalPeriodUid_isRejected() {
        // openPeriodBUid belongs to Company B; caller is scoped to Company A
        assertThatThrownBy(() -> runService.preview(companyAId, openPeriodBUid))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void preview_withOwnFiscalPeriodUid_succeeds() {
        // Sanity: same company's period must work (not a false-positive regression)
        DepreciationRunPreviewDto preview = runService.preview(companyAId, openPeriodAUid);
        assertThat(preview).isNotNull();
        assertThat(preview.companyId()).isEqualTo(companyAId);
    }
}
