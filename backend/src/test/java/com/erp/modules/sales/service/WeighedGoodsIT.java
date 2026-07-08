package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductBarcodeDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.SetProductWeighingRequest;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.domain.enums.DimensionType;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.OverrideLinePriceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.platform.common.money.MoneyDto;
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
 * Integration tests for first-class weighed goods (ADR-0044 D-1b) against real Postgres.
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>{@code setWeighing} persists the profile when the base unit is a WEIGHT unit, and rejects a
 *       product whose base unit is not a weight unit.</li>
 *   <li>Un-marking a product clears its tare / scale-step.</li>
 *   <li>A weighed sale line normalises the quantity to the product's scale division (HALF_UP).</li>
 *   <li>A weighed product rung on a non-weight unit is rejected at add-line time.</li>
 *   <li>A normal (by-count) product is completely unaffected by the weighed-goods path.</li>
 * </ol>
 *
 * <p>Only DRAFT invoices are exercised (no finalise), so no GL / cash / stock setup is needed.
 */
class WeighedGoodsIT extends PostgresIntegrationTest {

    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateSeeder       taxRateSeeder;
    @Autowired private CustomerService     customerService;
    @Autowired private AgentService        agentService;
    @Autowired private ProductService      productService;
    @Autowired private PriceListService    priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository   companies;
    @Autowired private BranchRepository    branches;
    @Autowired private AppUserRepository   users;
    @Autowired private PasswordEncoder     passwordEncoder;
    @Autowired private IamTestData         testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;

    private String kgUid;         // WEIGHT unit (fractional, 3 dp)
    private String pcsUid;        // COUNT unit
    private String priceListUid;
    private String customerUid;
    private String agentUid;
    private String riceUid;       // base unit KG — the weighed candidate
    private String widgetUid;     // base unit PCS — a normal by-count product

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Weighed IT Org"));
        company = companies.save(new Company(org, "WGH", "Weighed Co"));
        branch  = branches.save(new Branch(company, "WG1", "Weighed Branch"));

        AppUser root = new AppUser("wgh_root", passwordEncoder.encode("R00t!Pass1"), "Weighed Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();
        RequestContext.set(new RequestContext.Principal(
                rootId, "wgh_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());

        // KG as a real WEIGHT unit (3 dp, fractional) — an admin can create this directly.
        kgUid = unitService.create(new CreateUnitOfMeasureRequest(
                company.getUid(), "KG", "Kilogram", "kg", DimensionType.WEIGHT, (short) 3, true)).uid();
        pcsUid = unitService.create(new CreateUnitOfMeasureRequest(
                company.getUid(), "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        // Loose rice: base unit KG, priced per kg.
        ProductDto rice = productService.create(new CreateProductRequest(
                company.getUid(), null, "Rice (loose)", null,
                ProductType.GOODS, true, true, kgUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        riceUid = rice.uid();
        productService.setPrice(riceUid, new SetProductPriceRequest(priceListUid, new MoneyDto("2000", "TZS")));

        // Normal by-count product: base unit PCS.
        ProductDto widget = productService.create(new CreateProductRequest(
                company.getUid(), null, "Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        widgetUid = widget.uid();
        productService.setPrice(widgetUid, new SetProductPriceRequest(priceListUid, new MoneyDto("500", "TZS")));

        CustomerDto cust = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null));
        customerUid = cust.uid();

        AgentDto ag = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Counter Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null));
        agentUid = ag.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: setWeighing persists / validates the base unit dimension
    // =========================================================================

    @Test
    void setWeighing_weightBasedProduct_persistsProfile() {
        ProductDto dto = productService.setWeighing(riceUid, new SetProductWeighingRequest(
                true, new BigDecimal("0.010"), new BigDecimal("0.005")));

        assertThat(dto.weighed()).isTrue();
        assertThat(dto.tareWeight()).isEqualByComparingTo("0.010");
        assertThat(dto.scaleStep()).isEqualByComparingTo("0.005");
    }

    @Test
    void updateProduct_cannotDropWeighedProductOntoNonWeightUnit() {
        // Regression (persona finding): the /weighing endpoint enforces WEIGHT base unit, but a plain
        // product edit must not be able to switch a weighed product onto a COUNT unit via the back door.
        productService.setWeighing(riceUid, new SetProductWeighingRequest(true, null, new BigDecimal("0.005")));

        assertThatThrownBy(() -> productService.updateByUid(riceUid, new UpdateProductRequest(
                "Rice loose", null, ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)))
                .as("a weighed product cannot be edited onto a by-count base unit")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight unit");
    }

    @Test
    void setWeighing_countBasedProduct_rejected() {
        assertThatThrownBy(() -> productService.setWeighing(widgetUid,
                new SetProductWeighingRequest(true, null, null)))
                .as("A product whose base unit is not a weight unit cannot be marked weighed")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight base unit");
    }

    // =========================================================================
    // Bar 2: un-marking clears tare / scale-step
    // =========================================================================

    @Test
    void setWeighing_unmark_clearsTareAndScaleStep() {
        productService.setWeighing(riceUid, new SetProductWeighingRequest(
                true, new BigDecimal("0.010"), new BigDecimal("0.005")));

        ProductDto dto = productService.setWeighing(riceUid, new SetProductWeighingRequest(
                false, new BigDecimal("0.010"), new BigDecimal("0.005")));

        assertThat(dto.weighed()).isFalse();
        assertThat(dto.tareWeight()).isNull();
        assertThat(dto.scaleStep()).isNull();
    }

    // =========================================================================
    // Bar 3: a weighed sale line rounds the quantity to the scale division
    // =========================================================================

    @Test
    void addLine_weighedProduct_roundsQuantityToScaleStep() {
        productService.setWeighing(riceUid, new SetProductWeighingRequest(
                true, null, new BigDecimal("0.005")));

        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        // 0.813 kg → nearest 0.005 kg (HALF_UP) → 0.815 kg
        SalesInvoiceLineDto line = salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                riceUid, kgUid, new BigDecimal("0.813"), null, null));

        assertThat(line.quantity())
                .as("weighed quantity is normalised to the 0.005 kg scale division")
                .isEqualByComparingTo("0.815");
        assertThat(line.requestedQuantity())
                .as("the entered weight is preserved so the rounding is visible")
                .isEqualByComparingTo("0.813");
    }

    @Test
    void addLine_weighedProduct_weightRoundsToZero_friendlyError() {
        // Regression (persona finding): a weight that rounds down to zero must be rejected in plain
        // language, not by hitting the raw quantity>0 DB CHECK with an opaque constraint error.
        productService.setWeighing(riceUid, new SetProductWeighingRequest(true, null, new BigDecimal("0.005")));

        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        assertThatThrownBy(() -> salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                riceUid, kgUid, new BigDecimal("0.001"), null, null)))
                .as("a weight that rounds to zero is rejected in plain language")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too small");
    }

    @Test
    void addLine_weighedProduct_noScaleStep_keepsExactWeight() {
        productService.setWeighing(riceUid, new SetProductWeighingRequest(true, null, null));

        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        SalesInvoiceLineDto line = salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                riceUid, kgUid, new BigDecimal("0.810"), null, null));

        assertThat(line.quantity())
                .as("with no scale division configured the exact weighed quantity is kept")
                .isEqualByComparingTo("0.810");
    }

    // =========================================================================
    // Bar 4: a weighed product rung on a non-weight unit is rejected
    // =========================================================================

    @Test
    void addLine_weighedProduct_onCountUnit_rejected() {
        productService.setWeighing(riceUid, new SetProductWeighingRequest(true, null, null));

        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        assertThatThrownBy(() -> salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                riceUid, pcsUid, new BigDecimal("1"), null, null)))
                .as("a weighed product cannot be sold on a by-count unit")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sold by weight");
    }

    // =========================================================================
    // Bar 5: a normal by-count product is unaffected by the weighed-goods path
    // =========================================================================

    @Test
    void addLine_nonWeighedProduct_quantityUnchanged() {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        SalesInvoiceLineDto line = salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                widgetUid, pcsUid, new BigDecimal("2.5"), null, null));

        assertThatCode(() -> {}).doesNotThrowAnyException();
        assertThat(line.quantity())
                .as("a non-weighed product is never rounded by the weighed-goods rule")
                .isEqualByComparingTo("2.5");
    }

    // =========================================================================
    // Bar 6: bulk-pack unit dimension is validated (persona finding — Rehema #2)
    // =========================================================================

    @Test
    void addBulkPack_crossDimensionUnit_rejected() {
        // rice is measured by weight (KG). A VOLUME (litre) pack of it is nonsense.
        String litreUid = unitService.create(new CreateUnitOfMeasureRequest(
                company.getUid(), "LITRE", "Litre", "L", DimensionType.VOLUME, (short) 3, true)).uid();

        assertThatThrownBy(() -> productService.addBulkPack(riceUid, new CreateBulkPackRequest(
                litreUid, new BigDecimal("1"))))
                .as("a volume unit cannot be a pack of a weight-based product")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pack size");
    }

    @Test
    void addBulkPack_countUnit_allowed() {
        // A discrete count package (Pieces here, e.g. a bag/box) IS a valid pack of a weighed product.
        assertThatCode(() -> productService.addBulkPack(riceUid, new CreateBulkPackRequest(
                pcsUid, new BigDecimal("2"))))
                .as("a count unit is a valid pack of a weight-based product")
                .doesNotThrowAnyException();
    }

    // =========================================================================
    // Bar 7: a barcode can be bound to a specific unit (persona finding — Rehema #3)
    // =========================================================================

    @Test
    void addBarcode_boundToProductUnit_setsUom() {
        long pcsId = unitService.getByUid(pcsUid).id();
        ProductBarcodeDto bc = productService.addBarcode(widgetUid, new AddBarcodeRequest(
                "WIDGET-EA", false, pcsUid));

        assertThat(bc.uomId())
                .as("the barcode records which unit it addresses")
                .isEqualTo(pcsId);
    }

    @Test
    void addBarcode_unitNotValidForProduct_rejected() {
        // widget's base unit is PCS and it has no KG pack — a KG barcode makes no sense for it.
        assertThatThrownBy(() -> productService.addBarcode(widgetUid, new AddBarcodeRequest(
                "WIDGET-BADUNIT", false, kgUid)))
                .as("a barcode can only bind to the base unit or a configured pack unit")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a valid unit");
    }

    // =========================================================================
    // Bar 8: weighing validation messages are friendly (persona finding — Rehema #5)
    // =========================================================================

    @Test
    void setWeighing_negativeTare_friendlyMessageWithoutFieldName() {
        assertThatThrownBy(() -> productService.setWeighing(riceUid, new SetProductWeighingRequest(
                true, new BigDecimal("-0.5"), null)))
                .as("negative tare is rejected without leaking the raw field name")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Tare weight cannot be negative.");
    }

    // =========================================================================
    // Bar 9: max-weight ceiling (persona finding — Sabina #1)
    // =========================================================================

    @Test
    void addLine_weighedProduct_aboveMaxSaleWeight_rejected() {
        // 50 kg ceiling; keying "813" (meaning grams) would ring 813 kg — must be rejected.
        productService.setWeighing(riceUid, new SetProductWeighingRequest(
                true, null, null, new BigDecimal("50")));
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        assertThatThrownBy(() -> salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                riceUid, kgUid, new BigDecimal("813"), null, null)))
                .as("a weighed line above the ceiling is rejected as an implausible weight")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("maximum sale weight");
    }

    @Test
    void addLine_weighedProduct_withinMaxSaleWeight_ok() {
        productService.setWeighing(riceUid, new SetProductWeighingRequest(
                true, null, null, new BigDecimal("50")));
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));

        SalesInvoiceLineDto line = salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                riceUid, kgUid, new BigDecimal("2.5"), null, null));
        assertThat(line.quantity()).isEqualByComparingTo("2.5");
    }

    // =========================================================================
    // Bar 11: friendly guards from persona re-test (quantity + override price)
    // =========================================================================

    @Test
    void addLine_zeroQuantity_friendlyMessage() {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));
        assertThatThrownBy(() -> salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                widgetUid, pcsUid, new BigDecimal("0"), null, null)))
                .as("zero quantity is rejected in plain language, not a raw field-name message")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Quantity must be greater than zero.");
    }

    @Test
    void overrideLinePrice_nonPositive_rejected() {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));
        SalesInvoiceLineDto line = salesInvoiceService.addLine(draft.uid(), new AddInvoiceLineRequest(
                widgetUid, pcsUid, new BigDecimal("2"), null, null));

        assertThatThrownBy(() -> salesInvoiceService.overrideLinePrice(draft.uid(), line.uid(),
                new OverrideLinePriceRequest(new BigDecimal("-500"))))
                .as("a negative/zero override price is rejected (would zero the line total silently)")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }
}
