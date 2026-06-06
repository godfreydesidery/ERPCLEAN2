package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.platform.common.api.ForbiddenException;
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
 * Integration tests for {@link PartyBranchGuard} (BR-PARTY-01): a branch being associated with
 * a party MUST belong to the party's own company.
 *
 * <p>Seed: org → Company A (branch A1) and Company B (branch B1). Guard is tested on both
 * Customer and Supplier kinds to confirm the wiring is per-kind, not shared state.
 */
class PartyBranchGuardIT extends PostgresIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private SupplierService supplierService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company companyA;
    private Company companyB;
    private Branch branchA1;
    private Branch branchB1;
    private Long rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org = organisations.save(new Organisation("Guard Test Org"));
        companyA = companies.save(new Company(org, "GBCA", "Guard Co A"));
        companyB = companies.save(new Company(org, "GBCB", "Guard Co B"));

        branchA1 = branches.save(new Branch(companyA, "GB-A1", "Guard Branch A1"));
        branchB1 = branches.save(new Branch(companyB, "GB-B1", "Guard Branch B1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "gb_root", passwordEncoder.encode("RootPass1!"), "Guard Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        // Root context scoped to company A initially; tests switch context when needed
        RequestContext.set(new RequestContext.Principal(
                rootId, "gb_root", true, companyA.getId(), branchA1.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Customer: assign own-company branch -> succeeds; assign other-company branch -> rejected
    // -----------------------------------------------------------------------

    @Test
    void customer_assignSameCompanyBranch_succeeds() {
        CustomerDto customer = createCustomerInA("Same Co Customer", "TIN-SC");

        customerService.assignBranch(customer.uid(), new AssignPartyBranchRequest(branchA1.getUid()));

        List<PartyBranchDto> assigned = customerService.listBranches(customer.uid());
        assertThat(assigned).hasSize(1);
        assertThat(assigned.get(0).branchId()).isEqualTo(branchA1.getId());
    }

    @Test
    void customer_assignDifferentCompanyBranch_throwsForbidden_BR_PARTY_01() {
        CustomerDto customer = createCustomerInA("Cross Co Customer", "TIN-CC");

        // branchB1 belongs to company B, but customer belongs to company A
        assertThatThrownBy(() ->
                customerService.assignBranch(customer.uid(),
                        new AssignPartyBranchRequest(branchB1.getUid())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("BR-PARTY-01");
    }

    @Test
    void customer_removeBranch_succeeds() {
        CustomerDto customer = createCustomerInA("Remove Branch Customer", "TIN-RBC");
        customerService.assignBranch(customer.uid(), new AssignPartyBranchRequest(branchA1.getUid()));

        customerService.removeBranch(customer.uid(), branchA1.getUid());

        assertThat(customerService.listBranches(customer.uid())).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Supplier: BR-PARTY-01 guard is wired per kind — same rule applies
    // -----------------------------------------------------------------------

    @Test
    void supplier_assignSameCompanyBranch_succeeds() {
        SupplierDto supplier = createSupplierInA("Same Co Supplier", "TIN-SS");

        supplierService.assignBranch(supplier.uid(), new AssignPartyBranchRequest(branchA1.getUid()));

        List<PartyBranchDto> assigned = supplierService.listBranches(supplier.uid());
        assertThat(assigned).hasSize(1);
    }

    @Test
    void supplier_assignDifferentCompanyBranch_throwsForbidden_BR_PARTY_01() {
        SupplierDto supplier = createSupplierInA("Cross Co Supplier", "TIN-CS");

        // branchB1 belongs to company B, but supplier belongs to company A
        assertThatThrownBy(() ->
                supplierService.assignBranch(supplier.uid(),
                        new AssignPartyBranchRequest(branchB1.getUid())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("BR-PARTY-01");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CustomerDto createCustomerInA(String name, String tin) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "gb_root", true, companyA.getId(), branchA1.getId(), null));
        return customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, name, null,
                tin, false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null));
    }

    private SupplierDto createSupplierInA(String name, String tin) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "gb_root", true, companyA.getId(), branchA1.getId(), null));
        return supplierService.create(new CreateSupplierRequest(
                companyA.getId(), PartyType.BUSINESS, name, null,
                tin, false, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS));
    }
}
