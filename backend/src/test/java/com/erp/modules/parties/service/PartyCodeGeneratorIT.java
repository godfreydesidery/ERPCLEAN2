package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CreateOtherPartyRequest;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.OtherPartyDto;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for per-kind, per-company code sequence isolation (OQ-PARTY-01, BR-PARTY-08).
 *
 * <p>Verifies:
 * <ul>
 *   <li>CUSTOMER and SUPPLIER use separate sequences — each starts at 0001 in the same company.
 *   <li>Two customers in DIFFERENT companies each get CUST-0001 (per-company isolation).
 *   <li>Sequence increments correctly within a single kind/company partition.
 *   <li>All four kind prefixes produce the correct code prefix (CUST/SUPP/AGNT/OTHR).
 * </ul>
 */
class PartyCodeGeneratorIT extends PostgresIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private SupplierService supplierService;
    @Autowired private AgentService agentService;
    @Autowired private OtherPartyService otherPartyService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Long rootId;
    private Organisation org;
    private Company companyA;
    private Branch branchA;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Code Gen Org"));
        companyA = companies.save(new Company(org, "CGCA", "Code Gen Co A"));
        branchA = branches.save(new Branch(companyA, "CG-A1", "CG Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "cg_root", passwordEncoder.encode("RootPass1!"), "CG Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "cg_root", true, companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // OQ-PARTY-01: per-kind sequences are independent within the same company
    // -----------------------------------------------------------------------

    @Test
    void sameCompany_customerAndSupplier_bothGetCode0001_separateSequences() {
        CustomerDto customer = customerService.create(minimalBusinessCustomer(companyA.getId(), "TIN-CG1"));
        SupplierDto supplier = supplierService.create(minimalSupplier(companyA.getId(), "TIN-CG2"));

        assertThat(customer.code()).isEqualTo("CUST-0001");
        assertThat(supplier.code()).isEqualTo("SUPP-0001");
    }

    // -----------------------------------------------------------------------
    // Per-company isolation: two customers in different companies each get CUST-0001
    // -----------------------------------------------------------------------

    @Test
    void differentCompanies_eachCustomerGetsCust0001() {
        Company companyB = companies.save(new Company(org, "CGCB", "Code Gen Co B"));
        Branch branchB = branches.save(new Branch(companyB, "CG-B1", "CG Branch B1"));

        CustomerDto custA = customerService.create(minimalBusinessCustomer(companyA.getId(), "TIN-A1"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "cg_root", true, companyB.getId(), branchB.getId(), null));
        CustomerDto custB = customerService.create(minimalBusinessCustomer(companyB.getId(), "TIN-B1"));

        assertThat(custA.code()).isEqualTo("CUST-0001");
        assertThat(custB.code()).isEqualTo("CUST-0001");
        assertThat(custA.companyId()).isNotEqualTo(custB.companyId());
    }

    // -----------------------------------------------------------------------
    // Sequence increments correctly within a single kind/company
    // -----------------------------------------------------------------------

    @Test
    void sameCompanySameKind_threeCustomers_codesAreSequential() {
        CustomerDto c1 = customerService.create(minimalBusinessCustomer(companyA.getId(), "TIN-S1"));
        CustomerDto c2 = customerService.create(minimalBusinessCustomer(companyA.getId(), "TIN-S2"));
        CustomerDto c3 = customerService.create(minimalBusinessCustomer(companyA.getId(), "TIN-S3"));

        assertThat(c1.code()).isEqualTo("CUST-0001");
        assertThat(c2.code()).isEqualTo("CUST-0002");
        assertThat(c3.code()).isEqualTo("CUST-0003");
    }

    // -----------------------------------------------------------------------
    // All four kind prefixes produce the correct code prefix (CUST/SUPP/AGNT/OTHR)
    // -----------------------------------------------------------------------

    @Test
    void allFourKinds_prefixesAreCorrect() {
        CustomerDto cust = customerService.create(minimalBusinessCustomer(companyA.getId(), "TIN-FK1"));
        SupplierDto supp = supplierService.create(minimalSupplier(companyA.getId(), "TIN-FK2"));

        AgentDto agent = agentService.create(new CreateAgentRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "External Agent FK", null,
                null, false, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null));

        OtherPartyDto other = otherPartyService.create(new CreateOtherPartyRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Other FK", null,
                null, false, null, null, null, null, null, null, null, null, null, null));

        assertThat(cust.code()).startsWith("CUST-");
        assertThat(supp.code()).startsWith("SUPP-");
        assertThat(agent.code()).startsWith("AGNT-");
        assertThat(other.code()).startsWith("OTHR-");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static CreateCustomerRequest minimalBusinessCustomer(Long companyId, String tin) {
        return new CreateCustomerRequest(
                companyId, PartyType.BUSINESS, "Business " + tin, null,
                tin, false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null);
    }

    private static CreateSupplierRequest minimalSupplier(Long companyId, String tin) {
        return new CreateSupplierRequest(
                companyId, PartyType.BUSINESS, "Supplier " + tin, null,
                tin, false, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null);
    }
}
