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
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
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
 * Integration tests for {@link PriceListServiceImpl} (FR-PROD-10, ADR-0007 D-7).
 * Covers: create/getByUid/list/archive; cross-company scope guard.
 */
class PriceListServiceImplIT extends PostgresIntegrationTest {

    @Autowired private PriceListService priceListService;
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
        org = organisations.save(new Organisation("PL Test Org"));
        companyA = companies.save(new Company(org, "PLCA", "PriceList Co A"));
        branchA = branches.save(new Branch(companyA, "PL-A1", "PL Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "pl_root", passwordEncoder.encode("RootPass1!"), "PL Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "pl_root", true, companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void create_priceList_fieldsRoundTrip() {
        PriceListDto dto = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL", "Retail Price List"));

        assertThat(dto.code()).isEqualTo("RETAIL");
        assertThat(dto.name()).isEqualTo("Retail Price List");
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
        assertThat(dto.uid()).isNotBlank();
    }

    @Test
    void list_returnsOnlyOwnCompany() {
        Company companyB = companies.save(new Company(org, "PLCB", "PriceList Co B"));
        Branch branchB = branches.save(new Branch(companyB, "PL-B1", "PL Branch B1"));

        priceListService.create(new CreatePriceListRequest(companyA.getUid(), "RETAIL_A", "Retail A"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "pl_root", true, companyB.getId(), branchB.getId(), null));
        priceListService.create(new CreatePriceListRequest(companyB.getUid(), "RETAIL_B", "Retail B"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "pl_root", true, companyA.getId(), branchA.getId(), null));
        Page<PriceListDto> pageA = priceListService.list(companyA.getId(), null, Pageable.unpaged());
        assertThat(pageA.getTotalElements()).isEqualTo(1);
        assertThat(pageA.getContent().get(0).code()).isEqualTo("RETAIL_A");
    }

    @Test
    void list_crossCompany_nonRoot_throwsForbidden() {
        Company companyB = companies.save(new Company(org, "PLCC", "PriceList Co C"));

        com.erp.modules.iam.domain.entity.AppUser userA =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "pl_user_a", passwordEncoder.encode("Pass1!"), "PL User A");
        userA = users.save(userA);
        RequestContext.set(new RequestContext.Principal(
                userA.getId(), "pl_user_a", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() -> priceListService.list(companyB.getId(), null, Pageable.unpaged()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void archiveByUid_setsStatusArchived() {
        PriceListDto dto = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "WHSL", "Wholesale"));
        priceListService.archiveByUid(dto.uid());

        PriceListDto fetched = priceListService.getByUid(dto.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    // -----------------------------------------------------------------------
    // P2 D5 — pricing metadata settable via create/update
    // -----------------------------------------------------------------------

    @Test
    void create_withMetadata_persistsCurrencyDatesVatDefaultAndScope() {
        java.time.LocalDate from = java.time.LocalDate.of(2026, 1, 1);
        java.time.LocalDate to = java.time.LocalDate.of(2026, 12, 31);

        PriceListDto dto = priceListService.create(new CreatePriceListRequest(
                companyA.getUid(), "PROMO", "Promo List",
                "usd", from, to, true, true,
                com.erp.modules.products.domain.enums.PriceListScope.SEGMENT));

        assertThat(dto.currency()).isEqualTo("USD"); // CurrencyCode normalises to upper-case
        assertThat(dto.effectiveFrom()).isEqualTo(from);
        assertThat(dto.effectiveTo()).isEqualTo(to);
        assertThat(dto.priceIncludesVat()).isTrue();
        assertThat(dto.isDefault()).isTrue();
        assertThat(dto.scope())
                .isEqualTo(com.erp.modules.products.domain.enums.PriceListScope.SEGMENT);
    }

    @Test
    void create_withoutMetadata_keepsExclusiveEntityDefaultAndOtherEntityDefaults() {
        // ADR-0056 D-2: the service/DB default stays EXCLUSIVE when the caller omits the flag
        // (grandfathering + import safety). "Inclusive by default for new lists" is a UI
        // affordance — the create form pre-checks the toggle and sends priceIncludesVat=true.
        PriceListDto dto = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "PLAIN", "Plain"));

        assertThat(dto.currency()).isNull();
        assertThat(dto.effectiveFrom()).isNull();
        assertThat(dto.effectiveTo()).isNull();
        assertThat(dto.priceIncludesVat()).isFalse();
        assertThat(dto.isDefault()).isFalse();
        assertThat(dto.scope())
                .isEqualTo(com.erp.modules.products.domain.enums.PriceListScope.GLOBAL);
    }

    @Test
    void create_withExplicitFalseVat_keepsExclusive() {
        // An explicit false must still be honoured (not overridden by the new default).
        PriceListDto dto = priceListService.create(new CreatePriceListRequest(
                companyA.getUid(), "WHOLESALE_EXCL", "Wholesale Exclusive",
                null, null, null, false, null, null));

        assertThat(dto.priceIncludesVat()).isFalse();
    }

    @Test
    void update_withMetadata_overwritesPricingFields() {
        PriceListDto created = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "EDIT", "Edit Me"));

        PriceListDto updated = priceListService.updateByUid(created.uid(),
                new com.erp.modules.products.domain.dto.UpdatePriceListRequest(
                        "Edited", "EUR", null, null, true, false,
                        com.erp.modules.products.domain.enums.PriceListScope.BRANCH));

        assertThat(updated.name()).isEqualTo("Edited");
        assertThat(updated.currency()).isEqualTo("EUR");
        assertThat(updated.priceIncludesVat()).isTrue();
        assertThat(updated.scope())
                .isEqualTo(com.erp.modules.products.domain.enums.PriceListScope.BRANCH);
    }
}
