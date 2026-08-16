package com.erp.modules.stock.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.stock.domain.dto.CreateStockLocationRequest;
import com.erp.modules.stock.domain.dto.CreateVanReconciliationRequest;
import com.erp.modules.stock.domain.dto.EnterVanReconciliationLinesRequest;
import com.erp.modules.stock.domain.dto.StockLocationDto;
import com.erp.modules.stock.domain.dto.VanReconciliationDto;
import com.erp.modules.stock.domain.entity.VanReconciliation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.domain.enums.VanReconciliationStatus;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.VanReconciliationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link VanReconciliationServiceImpl} (ADR-0051 D-8) against a real
 * Postgres schema.
 *
 * <p>Covers: create -&gt; enter -&gt; reconcile derives loaded/returned from real TRANSFER_IN/OUT
 * ledger rows and posts ZERO stock movements on reconcile (the RECORD-ONLY invariant, D-8.2);
 * VAN.RECONCILED is emitted to the outbox; a duplicate live worksheet for the same van+date is
 * rejected; cross-company reads are denied; and the HTTP-level permission gate 403s a caller
 * without STOCK.VAN_RECON.MANAGE, then 201s once granted.
 */
@AutoConfigureMockMvc
class VanReconciliationIT extends PostgresIntegrationTest {

    @Autowired private VanReconciliationService vanReconciliationService;
    @Autowired private StockLocationService     stockLocationService;
    @Autowired private ProductService           productService;
    @Autowired private UnitOfMeasureService     unitService;
    @Autowired private AgentService             agentService;
    @Autowired private StockPostingService      posting;
    @Autowired private StockMovementRepository  stockMovementRepo;
    @Autowired private VanReconciliationRepository vanReconciliationRepo;
    @Autowired private DomainEventRepository    domainEventRepo;
    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private RoleRepository           roles;
    @Autowired private PermissionRepository     permissions;
    @Autowired private UserBranchRepository     userBranches;
    @Autowired private UserRoleService          userRoleService;
    @Autowired private JwtService               jwtService;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private PermissionResolver       permissionResolver;
    @Autowired private IamTestData              testData;
    @Autowired private MockMvc                  mockMvc;
    @Autowired private ObjectMapper             objectMapper;
    @Autowired private TransactionTemplate      txTemplate;

    private Company company;
    private Branch  branch;
    private AppUser rootUser;
    private AppUser plainUser;
    private String  plainToken;
    private String  pcsUid;
    private ProductDto product;
    private StockLocationDto van;

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 4);
    private static final String PLAIN_PASS = "VanPlainH1!z";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("Van Recon IT Org"));
        company = companies.save(new Company(org, "VRIT", "Van Recon IT Co"));
        branch  = new Branch(company, "VRIT1", "Van Recon IT Branch");
        branch.setDefault(true);
        branch  = branches.save(branch);

        rootUser = new AppUser("vr_root", passwordEncoder.encode("VrRootH1!z"), "VR Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("vr_plain", passwordEncoder.encode(PLAIN_PASS), "VR Plain");
        plainUser = users.save(inOrganisation(plainUser, org.getId()));
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branch, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        plainToken = jwtService.issueAccessToken(plainUser, company.getId(), branch.getId()).value();

        actAsRoot();

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();

        product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Route Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));

        AgentDto agent = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Hamisi Route Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null));

        van = stockLocationService.create(new CreateStockLocationRequest(
                "VAN-1", "Route Van 1", LocationType.VAN, branch.getUid(), false, agent.uid()));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Full flow: create (derives loaded/returned) -> enter -> reconcile.
    // RECORD-ONLY invariant: reconcile posts ZERO stock_movements.
    // -------------------------------------------------------------------------

    @Test
    void fullFlow_createEnterReconcile_derivesFromLedger_recordOnly_emitsOutboxEvent() {
        seedVanTransferMovements();

        long movementsBeforeCreate = stockMovementRepo.count();

        CreateVanReconciliationRequest createReq = new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, "Day-end", null);
        VanReconciliationDto created = vanReconciliationService.create(createReq);

        assertThat(created.status()).isEqualTo(VanReconciliationStatus.OPEN);
        assertThat(created.reconNumber()).startsWith("VR-");
        assertThat(created.vanLocationUid()).isEqualTo(van.uid());
        assertThat(created.agentUid()).isNotBlank();
        assertThat(created.lines()).hasSize(1);

        var line = created.lines().get(0);
        // loaded = Σ TRANSFER_IN in window = 100 ; returned = -Σ TRANSFER_OUT in window = 25
        // (the out-of-window TRANSFER_IN of 999 the day before must NOT be counted)
        assertThat(line.loadedQty()).isEqualByComparingTo("100");
        assertThat(line.returnedQty()).isEqualByComparingTo("25");
        assertThat(line.soldQty()).isEqualByComparingTo(BigDecimal.ZERO);
        // expected = loaded - sold - returned = 100 - 0 - 25 = 75
        assertThat(line.expectedQty()).isEqualByComparingTo("75");

        // create() derives from existing movements but posts none of its own.
        assertThat(stockMovementRepo.count()).isEqualTo(movementsBeforeCreate);

        // --- enter sold + physical count ---
        EnterVanReconciliationLinesRequest enterReq = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        null, product.uid(), new BigDecimal("70"), new BigDecimal("3"), null, null)));
        VanReconciliationDto entered = vanReconciliationService.enterLines(created.uid(), enterReq);

        var enteredLine = entered.lines().get(0);
        // expected = 100 - 70 - 25 = 5 ; variance = physical(3) - expected(5) = -2
        assertThat(enteredLine.expectedQty()).isEqualByComparingTo("5");
        assertThat(enteredLine.varianceQty()).isEqualByComparingTo("-2");

        long movementsBeforeReconcile = stockMovementRepo.count();

        VanReconciliationDto reconciled = vanReconciliationService.reconcile(created.uid());

        assertThat(reconciled.status()).isEqualTo(VanReconciliationStatus.RECONCILED);
        assertThat(reconciled.reconciledAt()).isNotNull();

        // --- RECORD-ONLY invariant (D-8.2): reconcile() posts ZERO stock_movements ---
        assertThat(stockMovementRepo.count())
                .as("reconcile() must not post any stock movement (record-only, ADR-0051 D-8.2)")
                .isEqualTo(movementsBeforeReconcile);

        // --- VAN.RECONCILED informational outbox event ---
        List<DomainEvent> reconciledEvents = domainEventRepo.findAll().stream()
                .filter(e -> DomainEventType.VAN_RECONCILED.equals(e.getEventType()))
                .filter(e -> reconciled.uid().equals(e.getAggregateUid()))
                .toList();
        assertThat(reconciledEvents).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Duplicate live worksheet per van/day rejected.
    // -------------------------------------------------------------------------

    @Test
    void create_duplicateLiveWorksheetForSameVanAndDate_rejected() {
        vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null));

        assertThatThrownBy(() -> vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void create_afterCancel_aFreshWorksheetIsAllowed() {
        VanReconciliationDto first = vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null));
        vanReconciliationService.cancel(first.uid());

        VanReconciliationDto second = vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null));

        assertThat(second.status()).isEqualTo(VanReconciliationStatus.OPEN);
        assertThat(second.uid()).isNotEqualTo(first.uid());
    }

    // -------------------------------------------------------------------------
    // enterLines(): sold-only entry — physicalQty is now OPTIONAL (backend review fix #1).
    // -------------------------------------------------------------------------

    @Test
    void enterLines_soldOnly_physicalOmitted_persistsAndRecomputesExpected_varianceZeroNotValue() {
        // No van transfer movements seeded — force a line for the product via productUids so the
        // worksheet has something to enter sold-only figures against (loaded=returned=0).
        VanReconciliationDto created = vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, List.of(product.uid())));
        var line = created.lines().get(0);
        assertThat(line.loadedQty()).isEqualByComparingTo(BigDecimal.ZERO);

        // No physicalQty in the request at all — the agent has only keyed sold so far.
        EnterVanReconciliationLinesRequest enterReq = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        null, product.uid(), new BigDecimal("12"), null, null, null)));

        VanReconciliationDto entered = vanReconciliationService.enterLines(created.uid(), enterReq);

        var enteredLine = entered.lines().get(0);
        assertThat(enteredLine.soldQty()).isEqualByComparingTo("12");
        assertThat(enteredLine.physicalQty()).isNull();
        // expected = loaded(0) - sold(12) - returned(0) = -12, computed regardless of physical.
        assertThat(enteredLine.expectedQty()).isEqualByComparingTo("-12");
        // variance_qty is NOT NULL DEFAULT 0 (V85 DDL) — stays ZERO until physical is entered.
        assertThat(enteredLine.varianceQty()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(enteredLine.varianceValue()).isNull();

        // A later call supplying physicalQty computes the real variance.
        EnterVanReconciliationLinesRequest secondEnter = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        null, product.uid(), new BigDecimal("12"), new BigDecimal("-10"), null, null)));
        VanReconciliationDto reEntered = vanReconciliationService.enterLines(created.uid(), secondEnter);
        var reEnteredLine = reEntered.lines().get(0);
        assertThat(reEnteredLine.physicalQty()).isEqualByComparingTo("-10");
        // variance = physical(-10) - expected(-12) = 2
        assertThat(reEnteredLine.varianceQty()).isEqualByComparingTo("2");
    }

    // -------------------------------------------------------------------------
    // cancel(): must require OPEN (backend review fix #3) — re-cancelling an already-CANCELLED
    // worksheet is rejected instead of re-stamping cancelled_at/cancelled_by.
    // -------------------------------------------------------------------------

    @Test
    void cancel_alreadyCancelled_rejected() {
        VanReconciliationDto created = vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null));
        vanReconciliationService.cancel(created.uid());

        assertThatThrownBy(() -> vanReconciliationService.cancel(created.uid()))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // Concurrency: enterLines() now version-touches the header (backend review fix #2) so it
    // participates in optimistic locking. Proof of mechanism: a header read is staled by a
    // concurrent write (cancel()) that commits first; replaying enterLines' own fix — touch() then
    // save() — against that stale header must fail optimistic locking instead of silently
    // overwriting a worksheet a race has just frozen.
    // -------------------------------------------------------------------------

    @Test
    void enterLinesHeaderTouch_staleAfterConcurrentCancel_rejectedByOptimisticLock() {
        VanReconciliationDto created = vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null));

        // Simulate enterLines() having already loaded the OPEN header in its own transaction,
        // before a concurrent cancel() races ahead and commits.
        VanReconciliation staleHeader = txTemplate.execute(status ->
                vanReconciliationRepo.findByUid(created.uid()).orElseThrow());

        // The concurrent cancel() commits first — bumps the header's @Version.
        vanReconciliationService.cancel(created.uid());

        // enterLines' fix (recon.touch(actorId); reconciliations.save(recon);) replayed against
        // the now-stale header must fail optimistic locking, not silently re-freeze/overwrite.
        staleHeader.touch(rootUser.getId());
        assertThatThrownBy(() -> txTemplate.execute(status -> vanReconciliationRepo.save(staleHeader)))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
    }

    // -------------------------------------------------------------------------
    // Cross-company denial (non-root)
    // -------------------------------------------------------------------------

    @Test
    void getByUid_crossCompany_nonRoot_forbidden() {
        VanReconciliationDto created = vanReconciliationService.create(new CreateVanReconciliationRequest(
                van.uid(), BUSINESS_DATE, null, null));

        Organisation org2 = organisations.save(new Organisation("Van Recon IT Org 2"));
        Company company2  = companies.save(new Company(org2, "VRIT2", "Van Recon IT Co 2"));
        Branch branch2    = branches.save(new Branch(company2, "VRIT2B", "Van Recon IT Branch 2"));
        AppUser nonRoot = users.save(inOrganisation(new AppUser(
                "vr_nonroot", passwordEncoder.encode("Pass1!"), "VR NonRoot"), org2.getId()));

        RequestContext.set(new RequestContext.Principal(
                nonRoot.getId(), "vr_nonroot", false, company2.getId(), branch2.getId(), null));

        String uid = created.uid();
        assertThatThrownBy(() -> vanReconciliationService.getByUid(uid))
                .as("a non-root caller from a different company must be denied (ADR-0051)")
                .isInstanceOf(ForbiddenException.class);
    }

    // -------------------------------------------------------------------------
    // HTTP-level permission gate: non-root without STOCK.VAN_RECON.MANAGE is 403; grant flips it
    // -------------------------------------------------------------------------

    @Test
    void plainUserWithoutPermission_create_returns403_thenGrantReturns201() throws Exception {
        Map<String, Object> body = Map.of(
                "vanLocationUid", van.uid(),
                "businessDate", BUSINESS_DATE.toString());

        mockMvc.perform(post("/api/v1/van-reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());

        grantRoleAsRoot(plainUser, buildRole("VAN_RECON_MANAGER", "STOCK.VAN_RECON.MANAGE"));

        mockMvc.perform(post("/api/v1/van-reconciliations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void actAsRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), "vr_root", true, company.getId(), branch.getId(), null));
    }

    /**
     * Seeds real ledger rows exactly as the transfer-dispatch/receive handlers would:
     * TRANSFER_IN +100 (loaded) and TRANSFER_OUT -25 (returned) at the van location, inside the
     * business-date window; plus an out-of-window TRANSFER_IN the day before that must be excluded.
     */
    private void seedVanTransferMovements() {
        Instant inWindow    = Instant.parse("2026-07-04T08:00:00Z");
        Instant outOfWindow = Instant.parse("2026-07-03T08:00:00Z");

        txTemplate.execute(status -> {
            posting.post(company.getId(), branch.getId(), van.id(), product.id(),
                    new BigDecimal("100"), MovementType.TRANSFER_IN,
                    null, "STOCK_TRANSFER", null, null, null,
                    inWindow, null, new BigDecimal("10.0000"), new BigDecimal("1000.0000"));
            posting.post(company.getId(), branch.getId(), van.id(), product.id(),
                    new BigDecimal("-25"), MovementType.TRANSFER_OUT,
                    null, "STOCK_TRANSFER", null, null, null,
                    inWindow, null, new BigDecimal("10.0000"), new BigDecimal("-250.0000"));
            // Out-of-window — must not be picked up by the [businessDate, +1day) derivation window.
            posting.post(company.getId(), branch.getId(), van.id(), product.id(),
                    new BigDecimal("999"), MovementType.TRANSFER_IN,
                    null, "STOCK_TRANSFER", null, null, null,
                    outOfWindow, null, new BigDecimal("10.0000"), new BigDecimal("9990.0000"));
            return null;
        });
    }

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by R__seed_permissions.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role) {
        actAsRoot();
        try {
            testData.seedMembership(user.getUid(), company.getUid());
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), null));
            permissionResolver.invalidate();
        } finally {
            RequestContext.clear();
        }
    }
}
