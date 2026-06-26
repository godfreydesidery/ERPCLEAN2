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
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.MoneyDto;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link CustomerServiceImpl} against real Postgres (Testcontainers).
 *
 * <p>Covers: CUST-#### code generation; business party requires TIN (BR-PARTY-04); individual
 * cash customer accepted without TIN (BR-PARTY-05/07); VRN without vat_registered rejected
 * (BR-PARTY-06); credit_limit Money both-or-neither; archive flips status; audit log written on
 * create (ADR-0004 D-12); per-company scope (list isolation); branch-assign cross-company guard
 * (BR-PARTY-01).
 */
class CustomerServiceImplIT extends PostgresIntegrationTest {

    @Autowired private CustomerService customerService;
    @Autowired private AuditRepository auditRepository;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    @Autowired private PriceListRepository priceListRepository;
    @Autowired private AgentRepository agentRepository;

    private Organisation org;
    private Company companyA;
    private Branch branchA;

    // root user stored id used in Principal
    private Long rootId;
    // real FK targets seeded per test run
    private Long priceListAId;
    private Long agentAId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org = organisations.save(new Organisation("Party Test Org"));
        companyA = companies.save(new Company(org, "PTYCA", "Party Co A"));
        branchA = branches.save(new Branch(companyA, "PTY-A1", "Party Branch A1"));

        // Persist a root app_user so ScopeGuard + audit actor resolve correctly
        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "pty_root", passwordEncoder.encode("RootPass1!"), "Parties Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();

        // ROOT context: canActIn any company; ScopeGuard passes for all seeds.
        RequestContext.set(new RequestContext.Principal(
                rootId, "pty_root", true, companyA.getId(), branchA.getId(), null));

        // Seed real FK targets in companyA so D5-defaults tests can reference real ids.
        PriceList pl = priceListRepository.save(
                new PriceList(companyA.getId(), "RETAIL", "Retail Price List", rootId));
        priceListAId = pl.getId();

        Agent ag = agentRepository.save(
                new Agent(companyA.getId(), "AGT-001", PartyType.INDIVIDUAL,
                        "Test Agent", AgentKind.EXTERNAL, rootId));
        agentAId = ag.getId();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Code allocation: first customer gets CUST-0001, second gets CUST-0002
    // -----------------------------------------------------------------------

    @Test
    void create_businessCreditCustomer_codeIsCust0001AndFieldsRoundTrip() {
        CreateCustomerRequest req = businessCreditRequest(companyA.getId(), "Acme Ltd",
                "TIN-001", true, "VRN-001", new MoneyDto("5000.00", "TZS"), 30);

        CustomerDto dto = customerService.create(req);

        assertThat(dto.code()).isEqualTo("CUST-0001");
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
        assertThat(dto.partyType()).isEqualTo(PartyType.BUSINESS);
        assertThat(dto.customerKind()).isEqualTo(CustomerKind.CREDIT_ACCOUNT);
        assertThat(dto.tin()).isEqualTo("TIN-001");
        assertThat(dto.vatRegistered()).isTrue();
        assertThat(dto.vrn()).isEqualTo("VRN-001");
        assertThat(dto.creditLimit()).isNotNull();
        assertThat(dto.creditLimit().amount()).isEqualTo("5000.00");
        assertThat(dto.creditLimit().currency()).isEqualTo("TZS");
        assertThat(dto.paymentTermsDays()).isEqualTo(30);
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
        assertThat(dto.uid()).isNotBlank();
    }

    @Test
    void create_secondCustomer_codeIsCust0002_sequenceIncrements() {
        customerService.create(businessCreditRequest(companyA.getId(), "First Co",
                "TIN-A", false, null, null, null));
        CustomerDto second = customerService.create(businessCreditRequest(companyA.getId(), "Second Co",
                "TIN-B", false, null, null, null));

        assertThat(second.code()).isEqualTo("CUST-0002");
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-04: business party without TIN -> rejected
    // -----------------------------------------------------------------------

    @Test
    void create_businessCustomerWithoutTin_throwsIllegalArgument() {
        CreateCustomerRequest req = new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "No TIN Co", null,
                null,   // tin = null
                false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null);

        assertThatThrownBy(() -> customerService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-PARTY-04");
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-05/07: individual cash customer — name only, no TIN required
    // -----------------------------------------------------------------------

    @Test
    void create_individualCashCustomer_acceptedWithoutTin() {
        CreateCustomerRequest req = new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "John Doe", null,
                null, false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null);

        CustomerDto dto = customerService.create(req);

        assertThat(dto.code()).isEqualTo("CUST-0001");
        assertThat(dto.partyType()).isEqualTo(PartyType.INDIVIDUAL);
        assertThat(dto.tin()).isNull();
        assertThat(dto.creditLimit()).isNull();
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-06: VRN without vat_registered -> rejected
    // -----------------------------------------------------------------------

    @Test
    void create_vrnWithoutVatRegistered_throwsIllegalArgument() {
        CreateCustomerRequest req = new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "VRN Violator", null,
                "TIN-VRN",
                false,       // vatRegistered = false
                "VRN-BAD",   // vrn set — invalid
                null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null);

        assertThatThrownBy(() -> customerService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-PARTY-06");
    }

    // -----------------------------------------------------------------------
    // credit_limit: amount-without-currency — both-or-neither (validated by MoneyDto)
    // MoneyDto.toMoney: if amount non-null but currency null → returns null (no Money built).
    // The DB CHECK (both or neither) is the backstop for partial data reaching the DB.
    // We test what the service does: a MoneyDto with only amount/currency set works; null dto -> null.
    // -----------------------------------------------------------------------

    @Test
    void create_creditLimitAmountAndCurrency_roundTrips() {
        CreateCustomerRequest req = businessCreditRequest(companyA.getId(), "Credit Co",
                "TIN-CRED", false, null, new MoneyDto("1000.50", "USD"), null);

        CustomerDto dto = customerService.create(req);

        assertThat(dto.creditLimit()).isNotNull();
        // BigDecimal.toPlainString() preserves the input scale; "1000.50" parsed from string
        // stays "1000.50" — we verify the numeric value and currency, not the exact string format.
        assertThat(new java.math.BigDecimal(dto.creditLimit().amount()))
                .isEqualByComparingTo(new java.math.BigDecimal("1000.50"));
        assertThat(dto.creditLimit().currency()).isEqualTo("USD");
    }

    @Test
    void create_noCreditLimit_creditLimitIsNull() {
        CreateCustomerRequest req = businessCreditRequest(companyA.getId(), "No Limit Co",
                "TIN-NL", false, null, null, null);

        CustomerDto dto = customerService.create(req);

        assertThat(dto.creditLimit()).isNull();
    }

    // -----------------------------------------------------------------------
    // Archive: status flips to ARCHIVED
    // -----------------------------------------------------------------------

    @Test
    void archiveByUid_setsStatusArchived() {
        CustomerDto created = customerService.create(businessCreditRequest(
                companyA.getId(), "Archive Me", "TIN-ARC", false, null, null, null));

        customerService.archiveByUid(created.uid());

        CustomerDto fetched = customerService.getByUid(created.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    // -----------------------------------------------------------------------
    // Audit: creating a customer writes an audit_log row (ADR-0004 D-12)
    // -----------------------------------------------------------------------

    @Test
    void create_writesAuditLog_withCustomerCreateActionAndTargetTypeCustomers() {
        CustomerDto dto = customerService.create(businessCreditRequest(
                companyA.getId(), "Audited Co", "TIN-AUD", false, null, null, null));

        List<AuditLog> logs = auditRepository.findAll();
        // Filter to CUSTOMER.CREATE rows (ROOT_BYPASS may also be present if company differs)
        List<AuditLog> createLogs = logs.stream()
                .filter(l -> AuditActions.CUSTOMER_CREATE.equals(l.getAction()))
                .toList();
        assertThat(createLogs).hasSize(1);
        AuditLog log = createLogs.get(0);
        assertThat(log.getTargetType()).isEqualTo("customers");
        assertThat(log.getActorUserId()).isEqualTo(rootId);
        assertThat(log.getTargetUid()).isEqualTo(dto.uid());
    }

    // -----------------------------------------------------------------------
    // Per-company scope: list returns only own-company customers
    // -----------------------------------------------------------------------

    @Test
    void list_returnsOnlyOwnCompanyCustomers() {
        Company companyB = companies.save(new Company(org, "PTYCB", "Party Co B"));

        // Create one customer in A, one in B (root can act in any company)
        customerService.create(businessCreditRequest(companyA.getId(), "Co A Customer",
                "TIN-LA", false, null, null, null));

        // Switch context to companyB to create B's customer
        Branch branchB = branches.save(new Branch(companyB, "PTY-B1", "Branch B1"));
        RequestContext.set(new RequestContext.Principal(
                rootId, "pty_root", true, companyB.getId(), branchB.getId(), null));
        customerService.create(businessCreditRequest(companyB.getId(), "Co B Customer",
                "TIN-LB", false, null, null, null));

        // List for company A — must see only A's customer
        RequestContext.set(new RequestContext.Principal(
                rootId, "pty_root", true, companyA.getId(), branchA.getId(), null));
        Page<CustomerDto> pageA = customerService.list(companyA.getId(), null, Pageable.unpaged());
        assertThat(pageA.getTotalElements()).isEqualTo(1);
        assertThat(pageA.getContent().get(0).displayName()).isEqualTo("Co A Customer");
    }

    // -----------------------------------------------------------------------
    // Branch association: BR-PARTY-01 — cross-company branch assign is REJECTED
    // -----------------------------------------------------------------------

    @Test
    void assignBranch_sameCo_succeeds() {
        CustomerDto dto = customerService.create(businessCreditRequest(
                companyA.getId(), "Branch Assignee", "TIN-BA", false, null, null, null));

        customerService.assignBranch(dto.uid(), new AssignPartyBranchRequest(branchA.getUid()));

        List<com.erp.modules.parties.domain.dto.PartyBranchDto> assigned =
                customerService.listBranches(dto.uid());
        assertThat(assigned).hasSize(1);
    }

    @Test
    void assignBranch_differentCo_throwsForbidden_BR_PARTY_01() {
        Company companyB = companies.save(new Company(org, "PTYBX", "Party Co BX"));
        Branch branchB = branches.save(new Branch(companyB, "PTY-BX1", "Branch BX1"));

        CustomerDto dto = customerService.create(businessCreditRequest(
                companyA.getId(), "Cross Company Guard", "TIN-CG", false, null, null, null));

        // Assign a branch that belongs to company B (not company A) — must be rejected
        assertThatThrownBy(() ->
                customerService.assignBranch(dto.uid(), new AssignPartyBranchRequest(branchB.getUid())))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("BR-PARTY-01");
    }

    @Test
    void removeBranch_afterAssign_branchListIsEmpty() {
        CustomerDto dto = customerService.create(businessCreditRequest(
                companyA.getId(), "Remove Branch", "TIN-RB", false, null, null, null));
        customerService.assignBranch(dto.uid(), new AssignPartyBranchRequest(branchA.getUid()));

        customerService.removeBranch(dto.uid(), branchA.getUid());

        assertThat(customerService.listBranches(dto.uid())).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Search: name match is CONTAINS (substring), case-insensitive — not prefix-only.
    // Regression for "cannot filter customers": a mid-name word must match.
    // -----------------------------------------------------------------------

    @Test
    void search_byMidNameWord_matchesContains_caseInsensitive() {
        customerService.create(businessCreditRequest(companyA.getId(), "Acme Traders Ltd",
                "TIN-ACME", false, null, null, null));
        customerService.create(businessCreditRequest(companyA.getId(), "Bob Mtu Supplies",
                "TIN-BOB", false, null, null, null));

        // Prefix still works
        assertThat(customerService.list(companyA.getId(), "Acme", Pageable.unpaged()).getContent())
                .extracting(CustomerDto::displayName).containsExactly("Acme Traders Ltd");
        // Mid-name word matches (the bug: previously empty under prefix-only LIKE 'q%')
        assertThat(customerService.list(companyA.getId(), "Traders", Pageable.unpaged()).getContent())
                .extracting(CustomerDto::displayName).containsExactly("Acme Traders Ltd");
        // Case-insensitive substring
        assertThat(customerService.list(companyA.getId(), "mtu", Pageable.unpaged()).getContent())
                .extracting(CustomerDto::displayName).containsExactly("Bob Mtu Supplies");
        // No spurious match
        assertThat(customerService.list(companyA.getId(), "zzz", Pageable.unpaged()).getContent())
                .isEmpty();
    }

    // -----------------------------------------------------------------------
    // P2 D5 / P2-mechanical — master-data defaults settable via create/update
    // -----------------------------------------------------------------------

    @Test
    void create_withD5Defaults_persistsCountrySegmentTaxAndDefaults() {
        CreateCustomerRequest req = new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "Defaults Co", null,
                "TIN-D5", true, "VRN-D5", null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null,
                "TZ", priceListAId, agentAId,
                com.erp.modules.parties.domain.enums.CustomerSegment.WHOLESALE,
                true, "EXEMPT-REF-1", "usd");

        CustomerDto dto = customerService.create(req);

        assertThat(dto.country()).isEqualTo("TZ");
        assertThat(dto.defaultPriceListId()).isEqualTo(priceListAId);
        assertThat(dto.defaultAgentId()).isEqualTo(agentAId);
        assertThat(dto.segment())
                .isEqualTo(com.erp.modules.parties.domain.enums.CustomerSegment.WHOLESALE);
        assertThat(dto.taxExempt()).isTrue();
        assertThat(dto.taxExemptionRef()).isEqualTo("EXEMPT-REF-1");
        assertThat(dto.defaultCurrency()).isEqualTo("USD"); // normalised upper-case
    }

    @Test
    void create_withoutD5Defaults_usesEntityDefaults() {
        CustomerDto dto = customerService.create(businessCreditRequest(companyA.getId(),
                "Plain Co", "TIN-PL", false, null, null, null));

        assertThat(dto.country()).isNull();
        assertThat(dto.defaultPriceListId()).isNull();
        assertThat(dto.defaultAgentId()).isNull();
        assertThat(dto.segment())
                .isEqualTo(com.erp.modules.parties.domain.enums.CustomerSegment.OTHER);
        assertThat(dto.taxExempt()).isFalse();
        assertThat(dto.defaultCurrency()).isNull();
    }

    @Test
    void update_withD5Defaults_overwritesFields() {
        CustomerDto created = customerService.create(businessCreditRequest(companyA.getId(),
                "Edit Co", "TIN-ED", false, null, null, null));

        var updated = customerService.updateByUid(created.uid(),
                new com.erp.modules.parties.domain.dto.UpdateCustomerRequest(
                        PartyType.BUSINESS, "Edit Co", null, "TIN-ED", false, null,
                        null, null, null, null, null, null, null, null,
                        CustomerKind.CREDIT_ACCOUNT, null, null, null,
                        "KE", priceListAId, agentAId,
                        com.erp.modules.parties.domain.enums.CustomerSegment.GOVERNMENT,
                        true, "REF-2", "kes"));

        assertThat(updated.country()).isEqualTo("KE");
        assertThat(updated.defaultPriceListId()).isEqualTo(priceListAId);
        assertThat(updated.defaultAgentId()).isEqualTo(agentAId);
        assertThat(updated.segment())
                .isEqualTo(com.erp.modules.parties.domain.enums.CustomerSegment.GOVERNMENT);
        assertThat(updated.taxExempt()).isTrue();
        assertThat(updated.taxExemptionRef()).isEqualTo("REF-2");
        assertThat(updated.defaultCurrency()).isEqualTo("KES");
    }

    // -----------------------------------------------------------------------
    // Private helpers
    // -----------------------------------------------------------------------

    private static CreateCustomerRequest businessCreditRequest(
            Long companyId, String name, String tin, boolean vatRegistered,
            String vrn, MoneyDto creditLimit, Integer paymentTermsDays) {
        return new CreateCustomerRequest(
                companyId, PartyType.BUSINESS, name, name + " Legal",
                tin, vatRegistered, vrn, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, creditLimit, paymentTermsDays, null);
    }
}
