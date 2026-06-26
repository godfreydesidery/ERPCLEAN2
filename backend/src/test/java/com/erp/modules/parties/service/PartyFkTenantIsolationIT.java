package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateCustomerRequest;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.domain.enums.PaymentTermsBasis;

import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.tax.domain.entity.WhtType;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTypeRepository;
import com.erp.platform.common.api.NotFoundException;
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
 * Regression test for the CONFUSED_DEPUTY FK-ownership vulnerability on customer and supplier create
 * / update (security fix 2026-06-26).
 *
 * <p>Scenario: Company-A admin supplies Company-B's {@code paymentTermsId}, {@code defaultPriceListId},
 * {@code defaultAgentId} (customer) or {@code defaultWhtTypeId} (supplier) in the request body.
 * The {@code scopeGuard.assertCanActIn} previously validated only the top-level {@code companyId},
 * not the FK targets — allowing cross-company reference data to be silently embedded. After the fix:
 * <ul>
 *   <li>A 404 {@link NotFoundException} is thrown (no existence-leak: same response as "not found in
 *       caller's company"), and</li>
 *   <li>No customer / supplier row is persisted (transaction rolls back).</li>
 * </ul>
 *
 * <p><b>Manual repro (against a running stack):</b>
 * <pre>
 *   # login as A_ADMIN, capture JWT; note Company-B's paymentTermsId, agentId etc.
 *   curl -X POST /api/v1/customers \
 *        -H "Authorization: Bearer <A_JWT>" \
 *        -d '{"companyId":<A_ID>,"paymentTermsId":<B_TERMS_ID>,...}'
 *   # Before fix: 201 Created. After fix: 404 Not Found.
 * </pre>
 */
class PartyFkTenantIsolationIT extends PostgresIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private SupplierService supplierService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    @Autowired private PaymentTermsRepository paymentTermsRepository;
    @Autowired private PriceListRepository priceListRepository;
    @Autowired private AgentRepository agentRepository;
    @Autowired private WhtTypeRepository whtTypeRepository;
    @Autowired private CustomerRepository customerRepository;
    @Autowired private SupplierRepository supplierRepository;

    private Company companyA;
    private Branch branchA;
    private Company companyB;

    // FK rows belonging to Company B — must not be usable by a Company-A caller
    private Long ptBId;      // PaymentTerms in B
    private Long plBId;      // PriceList in B
    private Long agBId;      // Agent in B
    private Long whtBId;     // WhtType in B

    // FK rows belonging to Company A — must be usable by a Company-A caller
    private Long ptAId;
    private Long plAId;
    private Long agAId;
    private Long whtAId;

    private Long rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Isolation Test Org"));
        companyA = companies.save(new Company(org, "ISO-A", "Isolation Co A"));
        branchA = branches.save(new Branch(companyA, "ISO-A1", "Isolation Branch A1"));
        companyB = companies.save(new Company(org, "ISO-B", "Isolation Co B"));
        branches.save(new Branch(companyB, "ISO-B1", "Isolation Branch B1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "iso_root", passwordEncoder.encode("RootPass1!"), "Isolation Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        // Root context — needed to seed FK rows in both companies.
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        // Seed Company-A FK masters
        ptAId = paymentTermsRepository.save(
                new PaymentTerms(companyA.getId(), "NET30-A", "Net 30 A",
                        PaymentTermsBasis.DAYS_AFTER_INVOICE, 30, rootId)).getId();
        plAId = priceListRepository.save(
                new PriceList(companyA.getId(), "RETAIL-A", "Retail A", rootId)).getId();
        agAId = agentRepository.save(
                new Agent(companyA.getId(), "AGT-A1", PartyType.INDIVIDUAL,
                        "Agent A", AgentKind.EXTERNAL, rootId)).getId();
        whtAId = whtTypeRepository.save(
                new WhtType(companyA.getId(), "WHT-A", "WHT A",
                        WhtKind.WHT_ON_PAYMENT, new BigDecimal("5.00"), rootId)).getId();

        // Seed Company-B FK masters (cross-company "foreign" rows)
        ptBId = paymentTermsRepository.save(
                new PaymentTerms(companyB.getId(), "NET30-B", "Net 30 B",
                        PaymentTermsBasis.DAYS_AFTER_INVOICE, 30, rootId)).getId();
        plBId = priceListRepository.save(
                new PriceList(companyB.getId(), "RETAIL-B", "Retail B", rootId)).getId();
        agBId = agentRepository.save(
                new Agent(companyB.getId(), "AGT-B1", PartyType.INDIVIDUAL,
                        "Agent B", AgentKind.EXTERNAL, rootId)).getId();
        whtBId = whtTypeRepository.save(
                new WhtType(companyB.getId(), "WHT-B", "WHT B",
                        WhtKind.WHT_ON_PAYMENT, new BigDecimal("3.00"), rootId)).getId();

        // Switch to non-root Company-A context for the actual assertions
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", false, companyA.getId(), branchA.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Customer — create with cross-company paymentTermsId
    // -----------------------------------------------------------------------

    @Test
    void createCustomer_withCrossCompanyPaymentTermsId_throwsNotFound_noRowPersisted() {
        long countBefore = customerRepository.count();

        assertThatThrownBy(() -> customerService.create(minimalCustomerRequest(ptBId, null, null)))
                .isInstanceOf(NotFoundException.class);

        assertThat(customerRepository.count()).isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // Customer — create with cross-company defaultPriceListId
    // -----------------------------------------------------------------------

    @Test
    void createCustomer_withCrossCompanyPriceListId_throwsNotFound_noRowPersisted() {
        long countBefore = customerRepository.count();

        assertThatThrownBy(() -> customerService.create(minimalCustomerRequest(null, plBId, null)))
                .isInstanceOf(NotFoundException.class);

        assertThat(customerRepository.count()).isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // Customer — create with cross-company defaultAgentId
    // -----------------------------------------------------------------------

    @Test
    void createCustomer_withCrossCompanyAgentId_throwsNotFound_noRowPersisted() {
        long countBefore = customerRepository.count();

        assertThatThrownBy(() -> customerService.create(minimalCustomerRequest(null, null, agBId)))
                .isInstanceOf(NotFoundException.class);

        assertThat(customerRepository.count()).isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // Customer — update with cross-company paymentTermsId
    // -----------------------------------------------------------------------

    @Test
    void updateCustomer_withCrossCompanyPaymentTermsId_throwsNotFound() {
        // Root creates a valid customer first
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));
        CustomerDto created = customerService.create(minimalCustomerRequest(null, null, null));

        // Switch to non-root A context and attempt cross-company FK on update
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() ->
                customerService.updateByUid(created.uid(), minimalCustomerUpdate(ptBId, null, null)))
                .isInstanceOf(NotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // Customer — own-company FKs are accepted
    // -----------------------------------------------------------------------

    @Test
    void createCustomer_withOwnCompanyFkIds_succeeds() {
        // Root context to bypass non-root restriction for positive control
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        CustomerDto dto = customerService.create(minimalCustomerRequest(ptAId, plAId, agAId));

        assertThat(dto.paymentTermsId()).isEqualTo(ptAId);
        assertThat(dto.defaultPriceListId()).isEqualTo(plAId);
        assertThat(dto.defaultAgentId()).isEqualTo(agAId);
    }

    // -----------------------------------------------------------------------
    // Supplier — create with cross-company paymentTermsId
    // -----------------------------------------------------------------------

    @Test
    void createSupplier_withCrossCompanyPaymentTermsId_throwsNotFound_noRowPersisted() {
        long countBefore = supplierRepository.count();

        assertThatThrownBy(() -> supplierService.create(minimalSupplierRequest(ptBId, null)))
                .isInstanceOf(NotFoundException.class);

        assertThat(supplierRepository.count()).isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // Supplier — create with cross-company defaultWhtTypeId
    // -----------------------------------------------------------------------

    @Test
    void createSupplier_withCrossCompanyWhtTypeId_throwsNotFound_noRowPersisted() {
        long countBefore = supplierRepository.count();

        assertThatThrownBy(() -> supplierService.create(minimalSupplierRequest(null, whtBId)))
                .isInstanceOf(NotFoundException.class);

        assertThat(supplierRepository.count()).isEqualTo(countBefore);
    }

    // -----------------------------------------------------------------------
    // Supplier — update with cross-company defaultWhtTypeId
    // -----------------------------------------------------------------------

    @Test
    void updateSupplier_withCrossCompanyWhtTypeId_throwsNotFound() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));
        SupplierDto created = supplierService.create(minimalSupplierRequest(null, null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", false, companyA.getId(), branchA.getId(), null));

        assertThatThrownBy(() ->
                supplierService.updateByUid(created.uid(), minimalSupplierUpdate(null, whtBId)))
                .isInstanceOf(NotFoundException.class);
    }

    // -----------------------------------------------------------------------
    // Supplier — own-company FKs are accepted
    // -----------------------------------------------------------------------

    @Test
    void createSupplier_withOwnCompanyFkIds_succeeds() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "iso_root", true, companyA.getId(), branchA.getId(), null));

        SupplierDto dto = supplierService.create(minimalSupplierRequest(ptAId, whtAId));

        assertThat(dto.defaultWhtTypeId()).isEqualTo(whtAId);
    }

    // -----------------------------------------------------------------------
    // Request builders
    // -----------------------------------------------------------------------

    private CreateCustomerRequest minimalCustomerRequest(
            Long paymentTermsId, Long defaultPriceListId, Long defaultAgentId) {
        return new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "Test Customer", null,
                "TIN-ISO", false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, paymentTermsId,
                null, defaultPriceListId, defaultAgentId, null, null, null, null);
    }

    private static UpdateCustomerRequest minimalCustomerUpdate(
            Long paymentTermsId, Long defaultPriceListId, Long defaultAgentId) {
        return new UpdateCustomerRequest(
                PartyType.BUSINESS, "Test Customer", null, "TIN-ISO", false, null,
                null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, paymentTermsId,
                null, defaultPriceListId, defaultAgentId, null, null, null, null);
    }

    private CreateSupplierRequest minimalSupplierRequest(Long paymentTermsId, Long whtTypeId) {
        return new CreateSupplierRequest(
                companyA.getId(), PartyType.BUSINESS, "Test Supplier", null,
                "TIN-SUP", false, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, paymentTermsId,
                null, null, null, null, whtTypeId);
    }

    private static UpdateSupplierRequest minimalSupplierUpdate(Long paymentTermsId, Long whtTypeId) {
        return new UpdateSupplierRequest(
                PartyType.BUSINESS, "Test Supplier", null, "TIN-SUP", false, null,
                null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, paymentTermsId,
                null, null, null, null, whtTypeId);
    }
}
