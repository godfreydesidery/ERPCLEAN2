package com.erp.modules.fixedassets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fixedassets.domain.dto.AssetCategoryDto;
import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.DepreciationRunDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationScheduleLineDto;
import com.erp.modules.fixedassets.domain.dto.DisposeAssetRequest;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.PlaceInServiceRequest;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.RunDepreciationRequest;
import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
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
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
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
 * Integration tests for Fixed Assets core flows (ADR-0030):
 * D-3 category CRUD, D-3 asset register + capitalisation, D-5 schedule generation,
 * D-4 depreciation run idempotency, D-8 outbox event, D-6c disposal journal.
 */
class FixedAssetServiceIT extends PostgresIntegrationTest {

    @Autowired private AssetCategoryService     categoryService;
    @Autowired private FixedAssetService        assetService;
    @Autowired private DepreciationRunService   runService;
    @Autowired private AssetDisposalService     disposalService;
    @Autowired private DepreciationScheduleService scheduleService;
    @Autowired private FixedAssetGlSeeder       glSeeder;

    @Autowired private AssetCategoryRepository  categoryRepo;
    @Autowired private FixedAssetRepository     assetRepo;
    @Autowired private ChartOfAccountRepository coaRepo;
    @Autowired private JournalEntryRepository   journalEntries;
    @Autowired private DomainEventRepository    domainEvents;

    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private PasswordEncoder          passwordEncoder;

    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;

    @Autowired private IamTestData iamTestData;

    private Long   companyId;
    private Long   branchId;
    private String openPeriodUid;
    private LocalDate openPeriodDate;

    // GL account IDs seeded by FixedAssetGlSeeder (1600/1700/5500)
    private Long assetAccountId;
    private Long accumDepAccountId;
    private Long depExpenseAccountId;

    @BeforeEach
    void setUp() {
        Organisation org = new Organisation("FA-Test-Org");
        organisations.save(org);

        Company co = new Company(org, "FA-CO", "FA Test Company");
        companies.save(co);
        companyId = co.getId();

        Branch br = new Branch(co, "FA-BR", "FA Test Branch");
        br.setDefault(true);
        branches.save(br);
        branchId = br.getId();

        AppUser user = new AppUser("fa-user", passwordEncoder.encode("Password1234"), "FA User");
        users.save(user);

        RequestContext.set(new RequestContext.Principal(user.getId(), "fa-user", false, companyId, branchId, null));

        // seed GL infra
        chartOfAccountService.seedDefaults(companyId);
        fiscalCalendarService.seedCurrentYear(companyId);
        glConfigService.seedDefaults(companyId);
        glSeeder.seedDefaults(companyId);

        // Resolve open period
        List<FiscalPeriodDto> periods = fiscalCalendarService.listPeriods(companyId);
        FiscalPeriodDto first = periods.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("No fiscal periods seeded"));
        openPeriodUid  = first.uid();
        openPeriodDate = first.startDate();

        // Resolve account IDs
        assetAccountId = coaRepo.findByCompanyIdAndAccountCode(companyId, "1600")
                .orElseThrow().getId();
        accumDepAccountId = coaRepo.findByCompanyIdAndAccountCode(companyId, "1700")
                .orElseThrow().getId();
        depExpenseAccountId = coaRepo.findByCompanyIdAndAccountCode(companyId, "5500")
                .orElseThrow().getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        iamTestData.clearAll();
    }

    // -------------------------------------------------------------------------
    // Category CRUD
    // -------------------------------------------------------------------------

    @Test
    void createCategory_persistsAndReturnsDto() {
        AssetCategoryDto dto = categoryService.create(makeVehicleCategoryReq());

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.code()).isEqualTo("VEHICLE");
        assertThat(dto.defaultMethod()).isEqualTo(DepreciationMethod.STRAIGHT_LINE);
        assertThat(categoryRepo.findByUid(dto.uid())).isPresent();
    }

    @Test
    void archiveCategory_setsArchivedStatus() {
        AssetCategoryDto cat = categoryService.create(makeVehicleCategoryReq());

        AssetCategoryDto archived = categoryService.archive(cat.uid());

        assertThat(archived.status().name()).isEqualTo("ARCHIVED");
    }

    // -------------------------------------------------------------------------
    // Asset register + place-in-service (D-3 / capitalisation journal D-7)
    // -------------------------------------------------------------------------

    @Test
    void register_createsAssetInDraftStatus() {
        AssetCategoryDto cat = categoryService.create(makeVehicleCategoryReq());

        FixedAssetDto asset = registerAsset(cat.id(), "Dell Laptop",
                new BigDecimal("2500000.00"), BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE, 36, null,
                openPeriodDate, openPeriodDate);

        assertThat(asset.uid()).isNotBlank();
        assertThat(asset.status()).isEqualTo(FixedAssetStatus.DRAFT);
        assertThat(asset.assetNumber()).startsWith("FA-");
        assertThat(assetRepo.findByUid(asset.uid())).isPresent();
    }

    @Test
    void placeInService_postsCapitalisationJournal_andScheduleGenerated() {
        AssetCategoryDto cat = categoryService.create(
                new CreateAssetCategoryRequest(
                        companyId, "EQUIP", "Equipment",
                        DepreciationMethod.STRAIGHT_LINE, 24, null,
                        assetAccountId, accumDepAccountId, depExpenseAccountId));

        FixedAssetDto asset = registerAsset(cat.id(), "Generator",
                new BigDecimal("5000000.00"), new BigDecimal("500000.00"),
                DepreciationMethod.STRAIGHT_LINE, 24, null,
                openPeriodDate, openPeriodDate);

        long journalsBefore = journalEntries.count();
        FixedAssetDto inService = assetService.placeInService(
                asset.uid(), new PlaceInServiceRequest(openPeriodDate));

        assertThat(inService.status()).isEqualTo(FixedAssetStatus.IN_SERVICE);
        assertThat(inService.capitalisedGlEntryUid()).isNotBlank();
        assertThat(journalEntries.count()).isGreaterThan(journalsBefore);

        // Depreciation schedule must have been generated (24 periods)
        List<DepreciationScheduleLineDto> schedule = scheduleService.listByAsset(asset.uid());
        assertThat(schedule).hasSize(24);

        // All charges should sum to depreciable amount = 5,000,000 - 500,000 = 4,500,000
        BigDecimal totalCharge = schedule.stream()
                .map(DepreciationScheduleLineDto::plannedCharge)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalCharge).isEqualByComparingTo(new BigDecimal("4500000.00"));
    }

    // -------------------------------------------------------------------------
    // Depreciation run — idempotency (D-4) + outbox event (D-8)
    // -------------------------------------------------------------------------

    @Test
    void depreciationRun_postsJournalAndEmitsOutboxEvent() {
        AssetCategoryDto cat = categoryService.create(
                new CreateAssetCategoryRequest(
                        companyId, "MACH", "Machinery",
                        DepreciationMethod.STRAIGHT_LINE, 12, null,
                        assetAccountId, accumDepAccountId, depExpenseAccountId));

        FixedAssetDto asset = registerAsset(cat.id(), "Lathe Machine",
                new BigDecimal("12000000.00"), BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE, 12, null,
                openPeriodDate, openPeriodDate);
        assetService.placeInService(asset.uid(), new PlaceInServiceRequest(openPeriodDate));

        long journalsBefore = journalEntries.count();
        long eventsBefore   = domainEvents.count();

        DepreciationRunDto run = runService.post(
                new RunDepreciationRequest(companyId, openPeriodUid, openPeriodDate));

        assertThat(run.uid()).isNotBlank();
        assertThat(run.runNumber()).startsWith("DEPR-");
        assertThat(run.glEntryUid()).isNotBlank();
        assertThat(journalEntries.count()).isGreaterThan(journalsBefore);

        // D-8: DEPRECIATION_RUN_EXECUTED outbox event published in same TX
        assertThat(domainEvents.count()).isGreaterThan(eventsBefore);
        boolean eventFound = domainEvents.findAll().stream().anyMatch(
                e -> DomainEventType.DEPRECIATION_RUN_EXECUTED.equals(e.getEventType()));
        assertThat(eventFound).isTrue();
    }

    @Test
    void depreciationRun_isIdempotent_secondRunRejected() {
        AssetCategoryDto cat = categoryService.create(
                new CreateAssetCategoryRequest(
                        companyId, "IDLE", "Idle Asset",
                        DepreciationMethod.STRAIGHT_LINE, 12, null,
                        assetAccountId, accumDepAccountId, depExpenseAccountId));

        FixedAssetDto asset = registerAsset(cat.id(), "Asset For Idempotency",
                new BigDecimal("6000000.00"), BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE, 12, null,
                openPeriodDate, openPeriodDate);
        assetService.placeInService(asset.uid(), new PlaceInServiceRequest(openPeriodDate));

        runService.post(new RunDepreciationRequest(companyId, openPeriodUid, openPeriodDate));

        // Second run for same company + period MUST be rejected (D-4 idempotency)
        assertThatThrownBy(() ->
                runService.post(new RunDepreciationRequest(companyId, openPeriodUid, openPeriodDate)))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Disposal GL journal (D-6c)
    // -------------------------------------------------------------------------

    @Test
    void disposeAsset_postsGlJournal_andStatusDisposed() {
        AssetCategoryDto cat = categoryService.create(
                new CreateAssetCategoryRequest(
                        companyId, "DISP", "Disposable",
                        DepreciationMethod.STRAIGHT_LINE, 6, null,
                        assetAccountId, accumDepAccountId, depExpenseAccountId));

        FixedAssetDto asset = registerAsset(cat.id(), "Old Vehicle",
                new BigDecimal("8000000.00"), BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE, 6, null,
                openPeriodDate, openPeriodDate);
        assetService.placeInService(asset.uid(), new PlaceInServiceRequest(openPeriodDate));

        // Run one depreciation period first
        runService.post(new RunDepreciationRequest(companyId, openPeriodUid, openPeriodDate));

        long journalsBefore = journalEntries.count();
        var disposal = disposalService.dispose(asset.uid(),
                new DisposeAssetRequest(openPeriodDate, new BigDecimal("5000000.00"), null));

        assertThat(disposal.uid()).isNotBlank();
        assertThat(disposal.proceedsAmount()).isEqualByComparingTo(new BigDecimal("5000000.00"));
        assertThat(disposal.glEntryUid()).isNotBlank();
        assertThat(journalEntries.count()).isGreaterThan(journalsBefore);

        // Asset should be DISPOSED
        FixedAssetDto reloaded = assetService.getByUid(asset.uid());
        assertThat(reloaded.status()).isEqualTo(FixedAssetStatus.DISPOSED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private CreateAssetCategoryRequest makeVehicleCategoryReq() {
        return new CreateAssetCategoryRequest(
                companyId, "VEHICLE", "Motor Vehicles",
                DepreciationMethod.STRAIGHT_LINE, 60, null,
                assetAccountId, accumDepAccountId, depExpenseAccountId);
    }

    private FixedAssetDto registerAsset(Long categoryId, String name,
                                         BigDecimal cost, BigDecimal salvage,
                                         DepreciationMethod method, int lifePeriods,
                                         BigDecimal reducingRate,
                                         LocalDate acquisitionDate, LocalDate depStartDate) {
        return assetService.register(new RegisterAssetRequest(
                companyId, branchId, categoryId, name,
                cost, salvage, method, lifePeriods, reducingRate,
                acquisitionDate, depStartDate,
                null, null, null));
    }
}
