package com.erp.modules.stock.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.StockTransferStatus;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An IN_TRANSIT stock transfer between two branches, dispatched and received through the real
 * outbox handlers against a real schema.
 *
 * <p>Regression for the cross-branch in-transit split: dispatch booked the in-transit leg under the
 * SOURCE branch while receive cleared it under the DESTINATION branch. On-hand is keyed by
 * (company, branch, location, product), so the two legs hit different rows — transit was left at +N
 * under one branch and -N under the other, and the value never reached the destination because the
 * receive-side {@code transferCost} found no cost on the row it looked at and skipped the move.
 */
class StockTransferCrossBranchIT extends PostgresIntegrationTest {

    @Autowired private StockTransferService    transferService;
    @Autowired private StockLocationSeeder     locationSeeder;
    @Autowired private LocationResolver        locationResolver;
    @Autowired private StockLocationRepository locations;
    @Autowired private StockOnHandRepository   onHands;
    @Autowired private ProductService          productService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private DomainEventRepository   domainEvents;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private UserBranchRepository    userBranches;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private PermissionResolver      permissionResolver;
    @Autowired private IamTestData             testData;
    @Autowired private TransactionTemplate     txTemplate;

    private Company company;
    private Branch  dar;
    private Branch  arusha;
    private AppUser rootUser;
    private ProductDto product;
    private StockLocation darMain;
    private StockLocation arushaMain;
    private Long arushaTransitId;

    private static final BigDecimal OPENING_QTY = new BigDecimal("100");
    private static final BigDecimal AVG_COST    = new BigDecimal("250.0000");
    private static final BigDecimal MOVED_QTY   = new BigDecimal("24");

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        Organisation org = organisations.save(new Organisation("Transfer IT Org"));
        company = companies.save(new Company(org, "XBIT", "Cross Branch IT Co"));
        Branch hq = new Branch(company, "DAR", "Dar es Salaam");
        hq.setDefault(true);
        dar    = branches.save(hq);
        arusha = branches.save(new Branch(company, "ARU", "Arusha"));

        locationSeeder.seedDefaults(company.getId(), dar.getId(), dar.getCode());
        locationSeeder.seedDefaults(company.getId(), arusha.getId(), arusha.getCode());
        darMain    = locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(company.getId(), dar.getId())
                .orElseThrow();
        arushaMain = locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(company.getId(), arusha.getId())
                .orElseThrow();
        arushaTransitId = locationResolver.inTransitLocationId(company.getId(), arusha.getId());

        rootUser = new AppUser("xb_root", passwordEncoder.encode("XbRootH1!z9"), "XB Root");
        rootUser.setRoot(true);
        rootUser = users.save(inOrganisation(rootUser, org.getId()));
        UserBranch darAssign = new UserBranch(rootUser.getId(), dar, rootUser.getId());
        darAssign.markDefault();
        userBranches.save(darAssign);
        userBranches.save(new UserBranch(rootUser.getId(), arusha, rootUser.getId()));

        actAsRoot();

        String pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Transfer Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));

        // Costed opening stock at Dar main: 100 @ 250 = 25,000.
        txTemplate.execute(s -> {
            StockOnHand soh = new StockOnHand(company.getId(), dar.getId(), darMain.getId(), product.id());
            soh.applyDelta(OPENING_QTY, rootUser.getId());
            soh.applyCostRecompute(AVG_COST, OPENING_QTY.multiply(AVG_COST), rootUser.getId());
            onHands.save(soh);
            return null;
        });
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void inTransitAcrossBranches_dispatchThenReceive_clearsTransitAndMovesQtyAndValue() {
        BigDecimal movedValue = MOVED_QTY.multiply(AVG_COST); // 6,000

        StockTransferDto draft = transferService.create(new CreateStockTransferRequest(
                darMain.getUid(), arushaMain.getUid(), LocalDate.now(), "IN_TRANSIT", null,
                List.of(new CreateStockTransferRequest.LineRequest(product.uid(), MOVED_QTY, null))));

        // --- Dispatch: source down, goods parked in Arusha's in-transit under ARUSHA ---
        transferService.dispatch(draft.uid());
        dispatchPending(DomainEventType.STOCK_TRANSFER_DISPATCHED);

        assertQty(dar, darMain.getId(), OPENING_QTY.subtract(MOVED_QTY));
        assertQty(arusha, arushaTransitId, MOVED_QTY);
        assertThat(row(dar, arushaTransitId))
                .as("nothing may be booked on the in-transit location under the SOURCE branch")
                .isEmpty();
        assertValue(arusha, arushaTransitId, movedValue);
        assertAvg(arusha, arushaTransitId, AVG_COST);

        // --- Receive: transit clears on the SAME row, destination gets qty AND value ---
        actAsRoot();
        StockTransferDto received = transferService.receive(draft.uid());
        dispatchPending(DomainEventType.STOCK_TRANSFER_RECEIVED);

        assertThat(received.status()).isEqualTo(StockTransferStatus.RECEIVED);
        assertQty(arusha, arushaTransitId, BigDecimal.ZERO);
        assertValue(arusha, arushaTransitId, BigDecimal.ZERO);
        assertQty(arusha, arushaMain.getId(), MOVED_QTY);
        assertValue(arusha, arushaMain.getId(), movedValue);
        assertAvg(arusha, arushaMain.getId(), AVG_COST);
        assertValue(dar, darMain.getId(), OPENING_QTY.subtract(MOVED_QTY).multiply(AVG_COST));
        assertThat(row(dar, arushaTransitId)).isEmpty();
    }

    /**
     * Regression for the double-counted destination qty in {@code transferCost}: the caller posts
     * the TRANSFER_IN first, so the destination row already holds the moved units. Adding them again
     * halved the destination's avg cost (24 worth 6,000 landed at an avg of 125, not 250).
     */
    @Test
    void instantAcrossBranches_destinationGetsQtyValueAndTheSourceAvgCost() {
        StockTransferDto draft = transferService.create(new CreateStockTransferRequest(
                darMain.getUid(), arushaMain.getUid(), LocalDate.now(), "INSTANT", null,
                List.of(new CreateStockTransferRequest.LineRequest(product.uid(), MOVED_QTY, null))));

        StockTransferDto done = transferService.completeInstant(draft.uid());

        assertThat(done.status()).isEqualTo(StockTransferStatus.COMPLETED);
        assertQty(dar, darMain.getId(), OPENING_QTY.subtract(MOVED_QTY));
        assertValue(dar, darMain.getId(), OPENING_QTY.subtract(MOVED_QTY).multiply(AVG_COST));
        assertQty(arusha, arushaMain.getId(), MOVED_QTY);
        assertValue(arusha, arushaMain.getId(), MOVED_QTY.multiply(AVG_COST));
        assertAvg(arusha, arushaMain.getId(), AVG_COST);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void actAsRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), "xb_root", true, company.getId(), dar.getId(), null));
    }

    /** Runs every PENDING event of the type through the real dispatcher and asserts it succeeded. */
    private void dispatchPending(String eventType) {
        List<DomainEvent> pending = domainEvents.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> e.getStatus() == DomainEventStatus.PENDING)
                .toList();
        pending.forEach(e -> dispatcher.dispatchOne(e.getId()));
        assertThat(domainEvents.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .map(DomainEvent::getStatus))
                .as(eventType + " must be dispatched successfully")
                .isNotEmpty()
                .allMatch(s -> s == DomainEventStatus.DISPATCHED);
    }

    private Optional<StockOnHand> row(Branch branch, Long locationId) {
        return onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                company.getId(), branch.getId(), locationId, product.id());
    }

    private void assertQty(Branch branch, Long locationId, BigDecimal expected) {
        assertThat(row(branch, locationId).map(StockOnHand::getQuantity).orElse(BigDecimal.ZERO))
                .as("qty at " + branch.getCode() + "/" + locationId)
                .isEqualByComparingTo(expected);
    }

    private void assertAvg(Branch branch, Long locationId, BigDecimal expected) {
        assertThat(row(branch, locationId).map(StockOnHand::getAvgCost).orElse(null))
                .as("avg cost at " + branch.getCode() + "/" + locationId)
                .isNotNull()
                .isEqualByComparingTo(expected);
    }

    private void assertValue(Branch branch, Long locationId, BigDecimal expected) {
        assertThat(row(branch, locationId).map(StockOnHand::getOnHandValue).orElse(BigDecimal.ZERO))
                .as("value at " + branch.getCode() + "/" + locationId)
                .isEqualByComparingTo(expected);
    }
}
