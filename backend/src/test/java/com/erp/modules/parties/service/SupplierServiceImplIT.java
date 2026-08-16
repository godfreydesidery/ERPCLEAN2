package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.tax.domain.entity.WhtType;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTypeRepository;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link SupplierServiceImpl} P2 D5 master-data defaults
 * (default_currency, lead_time_days, min_order_value, default_wht_type_id, country) — settable via
 * create/update and round-tripped on the read DTO.
 */
class SupplierServiceImplIT extends PostgresIntegrationTest {

    @Autowired private SupplierService supplierService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    @Autowired private WhtTypeRepository whtTypeRepository;

    private Company companyA;
    private Long whtTypeAId;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Supplier Test Org"));
        companyA = companies.save(new Company(org, "SUPCA", "Supplier Co A"));
        Branch branchA = branches.save(new Branch(companyA, "SUP-A1", "Supplier Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "sup_root", passwordEncoder.encode("RootPass1!"), "Supplier Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);

        RequestContext.set(new RequestContext.Principal(
                root.getId(), "sup_root", true, companyA.getId(), branchA.getId(), null, org.getId()));

        // Seed a real WhtType in companyA so D5-defaults tests can reference a real id.
        WhtType wht = whtTypeRepository.save(
                new WhtType(companyA.getId(), "WHT-STD", "Standard WHT",
                        WhtKind.WHT_ON_PAYMENT, new BigDecimal("5.00"), root.getId()));
        whtTypeAId = wht.getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void create_withD5Defaults_persistsCurrencyLeadTimeMinOrderWhtAndCountry() {
        CreateSupplierRequest req = new CreateSupplierRequest(
                companyA.getId(), PartyType.BUSINESS, "Defaults Supplier", null,
                "TIN-S5", false, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, 30, null,
                "TZ", "usd", 14, new BigDecimal("250.00"), whtTypeAId);

        SupplierDto dto = supplierService.create(req);

        assertThat(dto.country()).isEqualTo("TZ");
        assertThat(dto.defaultCurrency()).isEqualTo("USD"); // normalised upper-case
        assertThat(dto.leadTimeDays()).isEqualTo(14);
        assertThat(dto.minOrderValue()).isEqualByComparingTo("250.00");
        assertThat(dto.defaultWhtTypeId()).isEqualTo(whtTypeAId);
    }

    @Test
    void create_withoutD5Defaults_leavesFieldsNull() {
        SupplierDto dto = supplierService.create(new CreateSupplierRequest(
                companyA.getId(), PartyType.BUSINESS, "Plain Supplier", null,
                "TIN-PL", false, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));

        assertThat(dto.country()).isNull();
        assertThat(dto.defaultCurrency()).isNull();
        assertThat(dto.leadTimeDays()).isNull();
        assertThat(dto.minOrderValue()).isNull();
        assertThat(dto.defaultWhtTypeId()).isNull();
    }

    @Test
    void update_withD5Defaults_overwritesFields() {
        SupplierDto created = supplierService.create(new CreateSupplierRequest(
                companyA.getId(), PartyType.BUSINESS, "Edit Supplier", null,
                "TIN-ED", false, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));

        SupplierDto updated = supplierService.updateByUid(created.uid(), new UpdateSupplierRequest(
                PartyType.BUSINESS, "Edit Supplier", null, "TIN-ED", false, null,
                null, null, null, null, null, null, null, null,
                SupplierKind.SERVICE, null, null,
                "KE", "kes", 7, new BigDecimal("99.00"), whtTypeAId));

        assertThat(updated.supplierKind()).isEqualTo(SupplierKind.SERVICE);
        assertThat(updated.country()).isEqualTo("KE");
        assertThat(updated.defaultCurrency()).isEqualTo("KES");
        assertThat(updated.leadTimeDays()).isEqualTo(7);
        assertThat(updated.minOrderValue()).isEqualByComparingTo("99.00");
        assertThat(updated.defaultWhtTypeId()).isEqualTo(whtTypeAId);
    }
}
