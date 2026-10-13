package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.ProductPriceDto;
import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import com.erp.modules.products.domain.dto.SellingPriceQuery;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.domain.entity.CustomerPrice;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.enums.PriceSource;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.common.money.MoneyDto;
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
 * Selling-price resolution against real Postgres (PRD-01 / SAL-04 / POS-12 / LSF-02 / PRD-02).
 *
 * <p>Exercises {@code ProductPriceRepository.findPricingRowsOfProduct} (the JOIN FETCH query the
 * resolver chooses from) with rows written through the real services, so list status, list
 * validity dates, the default flag, pack rows and customer contract prices are all the shapes the
 * application actually persists.
 *
 * <p>The scenario is the live one: a retail list and a wholesale list, where whichever was typed
 * first used to price every sale for every customer.
 */
class SellingPriceResolutionIT extends PostgresIntegrationTest {

    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private PriceResolutionService priceResolution;
    @Autowired private UnitPriceLookupService priceLookup;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private CustomerRepository customers;
    @Autowired private CustomerPriceRepository customerPrices;
    @Autowired private PriceListRepository priceLists;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Long companyId;
    private UnitOfMeasureDto bottle;
    private UnitOfMeasureDto crate;
    private ProductDto lager;
    private PriceListDto wholesale;
    private PriceListDto retail;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Selling Price Org"));
        company = companies.save(new Company(org, "SPRX", "Selling Price Co"));
        companyId = company.getId();
        Branch branch = branches.save(new Branch(company, "SPR-A1", "Selling Price Branch"));

        AppUser root = new AppUser("selling_root", passwordEncoder.encode("RootPass1!"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "selling_root", true, companyId, branch.getId(), null, org.getId()));

        bottle = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "BTL", "Bottle"));
        crate = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "CRT", "Crate"));
        lager = productService.create(new CreateProductRequest(
                company.getUid(), null, "Lager 500ml", null, ProductType.GOODS, true, true,
                bottle.uid(), null, null, null, null, null, null, null, null, null, null, null));
        productService.addBulkPack(lager.uid(), new CreateBulkPackRequest(crate.uid(), new BigDecimal("20")));

        // WHOLESALE typed first — the live condition: under first-row-wins it priced everyone.
        wholesale = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "WHOLESALE", "Wholesale"));
        retail = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail"));
        price(wholesale, "2625.00", null);
        price(retail, "2750.00", null);
        price(retail, "54000.00", crate.uid());   // retail's explicit crate price (not 20 x 2750)
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void noDefaultSet_walkInKeepsTheLegacyFirstRow_noChangeForUnconfiguredCompanies() {
        assertThat(walkIn(bottle.id()).price().amount()).isEqualByComparingTo("2625.00");
    }

    @Test
    void companyDefault_pricesTheWalkIn_andTheTillListingPutsItFirst() {
        priceListService.setDefaultByUid(retail.uid());

        UnitPriceQuoteResult bottlePrice = walkIn(bottle.id());
        assertThat(bottlePrice.price().amount()).isEqualByComparingTo("2750.00");
        assertThat(bottlePrice.price().priceListUid()).isEqualTo(retail.uid());
        assertThat(walkIn(crate.id()).price().amount()).isEqualByComparingTo("54000.00");

        // The deployed till previews the FIRST row of this listing — it must be the walk-in price.
        List<ProductPriceDto> listing = productService.listPrices(lager.uid());
        ProductPriceDto firstBase = listing.stream().filter(p -> p.unitUid() == null).findFirst()
                .orElseThrow();
        assertThat(firstBase.priceListUid()).isEqualTo(retail.uid());
    }

    @Test
    void customerDefaultList_pricesThatCustomer_onEveryUnit_andThroughTheBatchRead() {
        priceListService.setDefaultByUid(retail.uid());
        Customer bar = customer("BAR-1", wholesale.id());

        UnitPriceQuoteResult barBottle = forCustomer(bar, bottle.id(), BigDecimal.ONE);
        assertThat(barBottle.price().amount()).isEqualByComparingTo("2625.00");
        assertThat(barBottle.price().source()).isEqualTo(PriceSource.LIST_PRICE);
        // Wholesale has no crate row: its own base x 20, never retail's crate price.
        assertThat(forCustomer(bar, crate.id(), BigDecimal.ONE).price().amount())
                .isEqualByComparingTo("52500.00");   // 2625 x 20

        ResolvedUnitPriceDto batch = priceLookup.resolve(new ResolveUnitPricesRequest(
                List.of(lager.uid()), crate.uid(), bar.getUid(), "TZS")).get(0);
        assertThat(batch.amount()).isEqualByComparingTo("52500.00");
        assertThat(batch.priceListUid()).isEqualTo(wholesale.uid());

        // The same request without the customer is the walk-in price.
        ResolvedUnitPriceDto walkInBatch = priceLookup.resolve(new ResolveUnitPricesRequest(
                List.of(lager.uid()), null)).get(0);
        assertThat(walkInBatch.amount()).isEqualByComparingTo("2750.00");
    }

    @Test
    void archivedList_neverPrices_evenAsTheCustomersListOrTheOldestRow() {
        Customer bar = customer("BAR-2", wholesale.id());
        priceListService.archiveByUid(wholesale.uid());

        assertThat(walkIn(bottle.id()).price().amount()).isEqualByComparingTo("2750.00");
        assertThat(forCustomer(bar, bottle.id(), BigDecimal.ONE).price().amount())
                .isEqualByComparingTo("2750.00");
    }

    @Test
    void listOutsideItsValidityWindow_isSkipped() {
        PriceList expired = priceLists.findById(wholesale.id()).orElseThrow();
        expired.setEffectiveTo(LocalDate.now().minusDays(1));
        priceLists.saveAndFlush(expired);

        assertThat(walkIn(bottle.id()).price().amount()).isEqualByComparingTo("2750.00");
    }

    @Test
    void everyListArchived_isNoPrice() {
        priceListService.archiveByUid(wholesale.uid());
        priceListService.archiveByUid(retail.uid());

        assertThat(walkIn(bottle.id()).status()).isEqualTo(UnitPriceStatus.NO_PRICE);
    }

    @Test
    void documentCurrency_prefersARowInThatCurrency() {
        PriceListDto dollars = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "EXPORT", "Export"));
        price(dollars, "1.10", null, "USD");

        UnitPriceQuoteResult usd = priceResolution.findSellingPriceQuote(new SellingPriceQuery(
                companyId, lager.id(), bottle.id(), null, null, "USD", null, null));
        UnitPriceQuoteResult tzs = priceResolution.findSellingPriceQuote(new SellingPriceQuery(
                companyId, lager.id(), bottle.id(), null, null, "TZS", null, null));

        assertThat(usd.price().amount()).isEqualByComparingTo("1.10");
        assertThat(usd.price().currency()).isEqualTo("USD");
        assertThat(tzs.price().amount()).isEqualByComparingTo("2625.00");
    }

    @Test
    void customerContractPrice_beatsTheirList_andScalesToThePack() {
        Customer bar = customer("BAR-3", wholesale.id());
        customerPrices.saveAndFlush(new CustomerPrice(companyId, bar.getId(), lager.id(),
                new BigDecimal("2500.00"), "TZS", null, null, null));

        UnitPriceQuoteResult barBottle = forCustomer(bar, bottle.id(), BigDecimal.ONE);
        assertThat(barBottle.price().amount()).isEqualByComparingTo("2500.00");
        assertThat(barBottle.price().source()).isEqualTo(PriceSource.CUSTOMER_PRICE);
        assertThat(forCustomer(bar, crate.id(), BigDecimal.ONE).price().amount())
                .isEqualByComparingTo("50000.00");   // 2500 x 20

        // Another customer never sees it.
        Customer other = customer("SHOP-1", null);
        assertThat(forCustomer(other, bottle.id(), BigDecimal.ONE).price().amount())
                .isEqualByComparingTo("2625.00");
    }

    // ---- helpers ----------------------------------------------------------------

    private void price(PriceListDto list, String amount, String unitUid) {
        price(list, amount, unitUid, "TZS");
    }

    private void price(PriceListDto list, String amount, String unitUid, String currency) {
        productService.setPrice(lager.uid(),
                new SetProductPriceRequest(list.uid(), new MoneyDto(amount, currency), unitUid));
    }

    private Customer customer(String code, Long defaultPriceListId) {
        Customer c = new Customer(companyId, code, PartyType.BUSINESS, "Customer " + code,
                CustomerKind.CREDIT_ACCOUNT, null);
        c.setDefaultPriceListId(defaultPriceListId);
        return customers.saveAndFlush(c);
    }

    private UnitPriceQuoteResult walkIn(Long unitId) {
        return priceResolution.findSellingPriceQuote(
                SellingPriceQuery.walkIn(companyId, lager.id(), unitId));
    }

    private UnitPriceQuoteResult forCustomer(Customer customer, Long unitId, BigDecimal qty) {
        return priceResolution.findSellingPriceQuote(new SellingPriceQuery(companyId, lager.id(),
                unitId, customer.getId(), customer.getDefaultPriceListId(), "TZS", qty, null));
    }
}
