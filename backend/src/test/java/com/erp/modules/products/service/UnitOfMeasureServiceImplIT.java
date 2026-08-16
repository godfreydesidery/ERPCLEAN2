package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link UnitOfMeasureServiceImpl} (brief §Tests).
 * Mirrors {@link PriceListServiceImplIT}: create/list/getByUid roundtrip;
 * cross-company list → Forbidden; code unique per company.
 */
class UnitOfMeasureServiceImplIT extends PostgresIntegrationTest {

    @Autowired private UnitOfMeasureService unitService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company companyA;
    private Branch branchA;
    private Long rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("UoM Test Org"));
        companyA = companies.save(new Company(org, "UOMCA", "UoM Co A"));
        branchA = branches.save(new Branch(companyA, "UOM-A1", "UoM Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "uom_root", passwordEncoder.encode("RootPass1!"), "UoM Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "uom_root", true, companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Create / getByUid / list roundtrip
    // -----------------------------------------------------------------------

    @Test
    void create_unitOfMeasure_fieldsRoundTrip() {
        UnitOfMeasureDto dto = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));

        assertThat(dto.code()).isEqualTo("PCS");
        assertThat(dto.name()).isEqualTo("Pieces");
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
        assertThat(dto.uid()).isNotBlank();
    }

    @Test
    void getByUid_returnsCreatedUnit() {
        UnitOfMeasureDto created = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "KG", "Kilogram"));

        UnitOfMeasureDto fetched = unitService.getByUid(created.uid());

        assertThat(fetched.uid()).isEqualTo(created.uid());
        assertThat(fetched.code()).isEqualTo("KG");
        assertThat(fetched.name()).isEqualTo("Kilogram");
    }

    @Test
    void list_returnsOnlyOwnCompany() {
        Company companyB = companies.save(new Company(org, "UOMCB", "UoM Co B"));
        Branch branchB = branches.save(new Branch(companyB, "UOM-B1", "UoM Branch B1"));

        unitService.create(new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS_A", "Pieces A"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "uom_root", true, companyB.getId(), branchB.getId(), null));
        unitService.create(new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS_B", "Pieces B"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "uom_root", true, companyA.getId(), branchA.getId(), null));
        Page<UnitOfMeasureDto> pageA = unitService.list(companyA.getId(), null, Pageable.unpaged());

        assertThat(pageA.getTotalElements()).isEqualTo(1);
        assertThat(pageA.getContent().get(0).code()).isEqualTo("PCS_A");
    }

    // -----------------------------------------------------------------------
    // Cross-company list → Forbidden (non-root)
    // -----------------------------------------------------------------------

    @Test
    void list_crossCompany_nonRoot_throwsForbidden() {
        Company companyB = companies.save(new Company(org, "UOMCC", "UoM Co C"));

        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "uom_user_a", passwordEncoder.encode("Pass1!"), "UoM User A");
        userA.setOrganisationId(org.getId());
        userA = users.save(userA);
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "uom_user_a", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> unitService.list(companyB.getId(), null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class);
    }

    // -----------------------------------------------------------------------
    // Code unique per company (duplicate → ConflictException)
    // -----------------------------------------------------------------------

    @Test
    void create_duplicateCode_sameCompany_throwsConflict() {
        unitService.create(new CreateUnitOfMeasureRequest(companyA.getUid(), "BOX", "Box"));

        assertThatThrownBy(() ->
                unitService.create(new CreateUnitOfMeasureRequest(companyA.getUid(), "BOX", "Box Again")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_duplicateCode_differentCompany_succeeds() {
        Company companyB = companies.save(new Company(org, "UOMCD", "UoM Co D"));
        Branch branchB = branches.save(new Branch(companyB, "UOM-D1", "UoM Branch D1"));

        unitService.create(new CreateUnitOfMeasureRequest(companyA.getUid(), "CARTON", "Carton A"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "uom_root", true, companyB.getId(), branchB.getId(), null));
        UnitOfMeasureDto dto = unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "CARTON", "Carton B"));

        assertThat(dto.code()).isEqualTo("CARTON");
        assertThat(dto.companyId()).isEqualTo(companyB.getId());
    }

    // -----------------------------------------------------------------------
    // Archive / restore
    // -----------------------------------------------------------------------

    @Test
    void archiveByUid_setsStatusArchived() {
        UnitOfMeasureDto dto = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PACK", "Pack"));
        unitService.archiveByUid(dto.uid());

        UnitOfMeasureDto fetched = unitService.getByUid(dto.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    @Test
    void restoreByUid_setsStatusActive() {
        UnitOfMeasureDto dto = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "CRATE", "Crate"));
        unitService.archiveByUid(dto.uid());
        unitService.restoreByUid(dto.uid());

        UnitOfMeasureDto fetched = unitService.getByUid(dto.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ACTIVE);
    }

    // -----------------------------------------------------------------------
    // P2 D5 — unit metadata settable via create/update (symbol/dimension/decimals/fractional)
    // -----------------------------------------------------------------------

    @Test
    void create_withMetadata_persistsDimensionDecimalsSymbolAndFractional() {
        UnitOfMeasureDto dto = unitService.create(new CreateUnitOfMeasureRequest(
                companyA.getUid(), "GRM", "Gram", "g",
                com.erp.modules.products.domain.enums.DimensionType.WEIGHT,
                (short) 3, true));

        assertThat(dto.symbol()).isEqualTo("g");
        assertThat(dto.dimensionType())
                .isEqualTo(com.erp.modules.products.domain.enums.DimensionType.WEIGHT);
        assertThat(dto.decimalPlaces()).isEqualTo((short) 3);
        assertThat(dto.fractional()).isTrue();
    }

    @Test
    void create_withoutMetadata_usesEntityDefaults() {
        UnitOfMeasureDto dto = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "EACH", "Each"));

        assertThat(dto.symbol()).isNull();
        assertThat(dto.dimensionType())
                .isEqualTo(com.erp.modules.products.domain.enums.DimensionType.COUNT);
        assertThat(dto.decimalPlaces()).isEqualTo((short) 0);
        assertThat(dto.fractional()).isTrue();
    }

    @Test
    void update_withMetadata_overwritesFields() {
        UnitOfMeasureDto created = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "LTR", "Litre"));

        UnitOfMeasureDto updated = unitService.updateByUid(created.uid(),
                new com.erp.modules.products.domain.dto.UpdateUnitOfMeasureRequest(
                        "Litre", "L",
                        com.erp.modules.products.domain.enums.DimensionType.VOLUME,
                        (short) 2, false));

        assertThat(updated.name()).isEqualTo("Litre");
        assertThat(updated.symbol()).isEqualTo("L");
        assertThat(updated.dimensionType())
                .isEqualTo(com.erp.modules.products.domain.enums.DimensionType.VOLUME);
        assertThat(updated.decimalPlaces()).isEqualTo((short) 2);
        assertThat(updated.fractional()).isFalse();
    }
}
