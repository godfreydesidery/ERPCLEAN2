package com.erp.modules.fixedassets.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.fixedassets.domain.dto.AssetCategoryDto;
import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.DisposeAssetRequest;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetReconciliationDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetRegisterRowDto;
import com.erp.modules.fixedassets.domain.dto.PlaceInServiceRequest;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.RevalueAssetRequest;
import com.erp.modules.fixedassets.domain.dto.RunDepreciationRequest;
import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
import com.erp.modules.gl.domain.dto.FiscalPeriodDto;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
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
import com.erp.platform.security.BranchReadGuard;
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
 * Fixed Asset Register (FR-FA-17) against real Postgres.
 *
 * <p>Scenario (year Y of the seeded calendar): asset A (1.2m, 12 periods) and asset B (600k,
 * 6 periods) are placed in service on 1 Jan with depreciation from 1 Feb; the February run posts
 * 100k on each; B is sold on 10 Feb; A is revalued up 300k on 15 Mar; C stays DRAFT.
 *
 * <p>The caller is a NON-root company member assigned to one branch — root short-circuits the
 * branch guard and would hide a missing check.
 */
class FixedAssetRegisterQueryIT extends PostgresIntegrationTest {

    @Autowired private FixedAssetRegisterQuery     registerQuery;
    @Autowired private FixedAssetReconQuery        reconQuery;
    @Autowired private AssetCategoryService        categoryService;
    @Autowired private FixedAssetService           assetService;
    @Autowired private DepreciationRunService      runService;
    @Autowired private AssetDisposalService        disposalService;
    @Autowired private AssetRevaluationService     revaluationService;
    @Autowired private FixedAssetGlSeeder          glSeeder;
    @Autowired private ChartOfAccountRepository    coaRepo;
    @Autowired private ChartOfAccountService       chartOfAccountService;
    @Autowired private FiscalCalendarService       fiscalCalendarService;
    @Autowired private GlConfigService             glConfigService;
    @Autowired private OrganisationRepository      organisations;
    @Autowired private CompanyRepository           companies;
    @Autowired private BranchRepository            branches;
    @Autowired private AppUserRepository           users;
    @Autowired private UserBranchRepository        userBranches;
    @Autowired private PasswordEncoder             passwordEncoder;
    @Autowired private IamTestData                 testData;

    private Organisation org;
    private Company company;
    private Branch  ownBranch;
    private Branch  otherBranch;
    private LocalDate jan1;
    private LocalDate feb1;
    private AssetCategoryDto category;
    private FixedAssetDto assetA;
    private FixedAssetDto assetB;
    private FixedAssetDto assetC;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org         = organisations.save(new Organisation("FA Register IT Org"));
        company     = companies.save(new Company(org, "FAREG", "FA Register IT Co"));
        ownBranch   = branches.save(new Branch(company, "FAREG1", "Own Branch"));
        otherBranch = branches.save(new Branch(company, "FAREG2", "Other Branch"));

        AppUser u = new AppUser("fareg_clerk", passwordEncoder.encode("FaReg@1!Xx"), "FA Clerk");
        u.setOrganisationId(org.getId());
        AppUser clerk = users.save(u);
        testData.seedMembership(clerk.getUid(), company.getUid());
        userBranches.save(new UserBranch(clerk.getId(), ownBranch, clerk.getId()));
        RequestContext.set(new RequestContext.Principal(
                clerk.getId(), "fareg_clerk", false, company.getId(), ownBranch.getId(), null,
                org.getId()));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        glSeeder.seedDefaults(company.getId());

        List<FiscalPeriodDto> periods = fiscalCalendarService.listPeriods(company.getId());
        jan1 = periods.stream().map(FiscalPeriodDto::startDate).min(LocalDate::compareTo).orElseThrow();
        feb1 = jan1.plusMonths(1);
        String febPeriodUid = periods.stream().filter(p -> p.startDate().equals(feb1))
                .findFirst().orElseThrow().uid();

        category = categoryService.create(new CreateAssetCategoryRequest(
                company.getId(), "EQUIP", "Equipment", DepreciationMethod.STRAIGHT_LINE, 12, null,
                account("1600"), account("1700"), account("5500")));

        assetA = register("Forklift", "1200000", 12, ownBranch, "Head Office Yard");
        assetB = register("Delivery Van", "600000", 6, ownBranch, "Depot");
        assetC = register("Spare Generator", "50000", 12, otherBranch, "Store");
        assetService.placeInService(assetA.uid(), new PlaceInServiceRequest(jan1));
        assetService.placeInService(assetB.uid(), new PlaceInServiceRequest(jan1));

        runService.post(new RunDepreciationRequest(company.getId(), febPeriodUid, feb1));
        disposalService.dispose(assetB.uid(),
                new DisposeAssetRequest(feb1.plusDays(9), new BigDecimal("400000"), null));
        revaluationService.revalue(assetA.uid(), new RevalueAssetRequest(
                RevaluationDirection.UP, new BigDecimal("300000"), jan1.plusMonths(2).plusDays(14),
                "Market uplift"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void atYearEnd_agreesWithTheReconciliation_toTheCent() {
        LocalDate yearEnd = LocalDate.of(jan1.getYear(), 12, 31);
        FixedAssetRegisterDto dto = registerQuery.register(company.getId(), yearEnd,
                null, null, null, null, null);
        FixedAssetReconciliationDto recon = reconQuery.reconcile(company.getId());

        assertThat(dto.rows()).hasSize(3);
        assertThat(dto.grandTotal().assetCount()).isEqualTo(1);
        assertThat(dto.grandTotal().cost()).isEqualByComparingTo(recon.registerCostSum());
        assertThat(dto.grandTotal().accumulatedDepreciation())
                .isEqualByComparingTo(recon.registerAccumDepSum());
        assertThat(dto.grandTotal().nbv()).isEqualByComparingTo(
                recon.registerCostSum().subtract(recon.registerAccumDepSum()));
        assertThat(dto.categoryTotals()).singleElement()
                .satisfies(t -> assertThat(t.nbv()).isEqualByComparingTo("1400000"));

        FixedAssetRegisterRowDto a = row(dto, assetA);
        assertThat(a.status()).isEqualTo(FixedAssetStatus.IN_SERVICE);
        assertThat(a.cost()).isEqualByComparingTo("1500000");
        assertThat(a.accumulatedDepreciation()).isEqualByComparingTo("100000");
        assertThat(a.nbv()).isEqualByComparingTo("1400000");
        assertThat(a.branchName()).isEqualTo("Own Branch");

        FixedAssetRegisterRowDto b = row(dto, assetB);
        assertThat(b.status()).isEqualTo(FixedAssetStatus.DISPOSED);
        assertThat(b.nbv()).as("a sold asset has no book value — blank, not zero").isNull();
        assertThat(b.inTotals()).isFalse();

        FixedAssetRegisterRowDto c = row(dto, assetC);
        assertThat(c.status()).isEqualTo(FixedAssetStatus.DRAFT);
        assertThat(c.nbv()).isNull();
        assertThat(dto.rowsNotInTotals()).isEqualTo(2);
    }

    @Test
    void asAtABackDate_rollsBackRevaluationDepreciationAndDisposal() {
        FixedAssetRegisterDto dto = registerQuery.register(company.getId(), jan1.plusDays(14),
                null, null, null, null, null);

        FixedAssetRegisterRowDto a = row(dto, assetA);
        assertThat(a.cost()).as("revalued in March — before that the cost was 1.2m")
                .isEqualByComparingTo("1200000");
        assertThat(a.accumulatedDepreciation()).as("first charge is for February")
                .isEqualByComparingTo("0");
        FixedAssetRegisterRowDto b = row(dto, assetB);
        assertThat(b.status()).as("sold in February — still in service mid-January")
                .isEqualTo(FixedAssetStatus.IN_SERVICE);
        assertThat(b.nbv()).isEqualByComparingTo("600000");
        assertThat(dto.grandTotal().assetCount()).isEqualTo(2);
        assertThat(dto.grandTotal().cost()).isEqualByComparingTo("1800000");

        FixedAssetRegisterDto feb5 = registerQuery.register(company.getId(), feb1.plusDays(4),
                null, null, null, null, null);
        assertThat(row(feb5, assetA).accumulatedDepreciation()).isEqualByComparingTo("100000");
        assertThat(row(feb5, assetB).accumulatedDepreciation()).isEqualByComparingTo("100000");
        assertThat(feb5.grandTotal().nbv()).isEqualByComparingTo("1600000");

        FixedAssetRegisterDto beforeAnything = registerQuery.register(company.getId(),
                jan1.minusDays(1), null, null, null, null, null);
        assertThat(beforeAnything.rows()).isEmpty();
        assertThat(beforeAnything.grandTotal().cost()).isEqualByComparingTo("0");
    }

    @Test
    void filters_narrowTheRows() {
        LocalDate yearEnd = LocalDate.of(jan1.getYear(), 12, 31);

        FixedAssetRegisterDto disposed = registerQuery.register(company.getId(), yearEnd,
                null, FixedAssetStatus.DISPOSED, null, null, null);
        assertThat(disposed.rows()).extracting(FixedAssetRegisterRowDto::assetUid)
                .containsExactly(assetB.uid());
        assertThat(disposed.grandTotal().assetCount()).isZero();

        FixedAssetRegisterDto yard = registerQuery.register(company.getId(), yearEnd,
                null, null, null, "office", null);
        assertThat(yard.rows()).extracting(FixedAssetRegisterRowDto::assetUid)
                .containsExactly(assetA.uid());

        FixedAssetRegisterDto byCategory = registerQuery.register(company.getId(), yearEnd,
                category.uid(), null, ownBranch.getUid(), null, null);
        assertThat(byCategory.categoryName()).isEqualTo("Equipment");
        assertThat(byCategory.branchName()).isEqualTo("Own Branch");
        assertThat(byCategory.rows()).extracting(FixedAssetRegisterRowDto::assetUid)
                .containsExactlyInAnyOrder(assetA.uid(), assetB.uid());
    }

    @Test
    void unknownFilterUids_areRefused_neverSilentlyWidened() {
        LocalDate d = LocalDate.of(jan1.getYear(), 12, 31);
        assertThatThrownBy(() -> registerQuery.register(company.getId(), d,
                "NOSUCHCATEGORY000000000000", null, null, null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> registerQuery.register(company.getId(), d,
                null, null, "NOSUCHBRANCH00000000000000", null, null))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> registerQuery.register(company.getId(), d,
                null, null, null, null, "NOSUCHCOSTCENTRE0000000000"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aBranchTheCallerIsNotAssignedTo_isRefused() {
        LocalDate d = LocalDate.of(jan1.getYear(), 12, 31);
        assertThatCode(() -> registerQuery.register(company.getId(), d,
                null, null, ownBranch.getUid(), null, null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> registerQuery.register(company.getId(), d,
                null, null, otherBranch.getUid(), null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(BranchReadGuard.branchNotAssigned().getMessage());
    }

    @Test
    void anotherCompanysCaller_isRefused() {
        Company other = companies.save(new Company(org, "FAOTH", "FA Other Co"));
        Branch otherCoBranch = branches.save(new Branch(other, "FAOTH1", "Other Co Branch"));
        AppUser outsider = new AppUser("fareg_outsider", passwordEncoder.encode("Outsider1!x"), "Outsider");
        outsider.setOrganisationId(org.getId());
        outsider = users.save(outsider);
        testData.seedMembership(outsider.getUid(), other.getUid());
        RequestContext.set(new RequestContext.Principal(outsider.getId(), "fareg_outsider", false,
                other.getId(), otherCoBranch.getId(), null, org.getId()));

        assertThatThrownBy(() -> registerQuery.register(company.getId(), LocalDate.now(),
                null, null, null, null, null))
                .isInstanceOf(ForbiddenException.class);
        // And the outsider's own company cannot reach this company's category by uid.
        assertThatThrownBy(() -> registerQuery.register(other.getId(), LocalDate.now(),
                category.uid(), null, null, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void statusAsAt_rules() {
        LocalDate d = LocalDate.of(2026, 6, 30);
        assertThat(FixedAssetRegisterQuery.statusAsAt(FixedAssetStatus.DRAFT, null, null, d))
                .isEqualTo(FixedAssetStatus.DRAFT);
        assertThat(FixedAssetRegisterQuery.statusAsAt(FixedAssetStatus.IN_SERVICE, d.plusDays(1), null, d))
                .isEqualTo(FixedAssetStatus.DRAFT);
        assertThat(FixedAssetRegisterQuery.statusAsAt(FixedAssetStatus.IN_SERVICE, d, null, d))
                .isEqualTo(FixedAssetStatus.IN_SERVICE);
        assertThat(FixedAssetRegisterQuery.statusAsAt(FixedAssetStatus.WRITTEN_OFF, d.minusDays(9), d.plusDays(1), d))
                .isEqualTo(FixedAssetStatus.IN_SERVICE);
        assertThat(FixedAssetRegisterQuery.statusAsAt(FixedAssetStatus.DISPOSED, d.minusDays(9), d, d))
                .isEqualTo(FixedAssetStatus.DISPOSED);
    }

    // -------------------------------------------------------------------------

    private Long account(String code) {
        return coaRepo.findByCompanyIdAndAccountCode(company.getId(), code).orElseThrow().getId();
    }

    private FixedAssetDto register(String name, String cost, int life, Branch branch, String location) {
        return assetService.register(new RegisterAssetRequest(
                company.getId(), branch.getId(), category.id(), name,
                new BigDecimal(cost), BigDecimal.ZERO, DepreciationMethod.STRAIGHT_LINE, life, null,
                jan1, feb1, location, null, null));
    }

    private static FixedAssetRegisterRowDto row(FixedAssetRegisterDto dto, FixedAssetDto asset) {
        return dto.rows().stream().filter(r -> r.assetUid().equals(asset.uid()))
                .findFirst().orElseThrow();
    }
}
