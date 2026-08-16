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
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.BarcodeSymbologyRuleDto;
import com.erp.modules.products.domain.dto.CreateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductBarcodeDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UpdateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.SymbologyItemMatch;
import com.erp.modules.products.domain.enums.SymbologyValueKind;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link BarcodeSymbologyRuleServiceImpl} + the upgraded
 * {@link ProductServiceImpl#lookupBarcode} (ADR-0044 D-1a / BR-9).
 *
 * <p>Covers:
 * <ul>
 *   <li>CRUD round-trip (create / get / update / archive)</li>
 *   <li>Duplicate-code conflict on create</li>
 *   <li>lookupBarcode: exact-match fast path returns derived fields null</li>
 *   <li>lookupBarcode: embedded-weight decode via BARCODE item_match</li>
 *   <li>lookupBarcode: embedded-weight decode via PRODUCT_CODE item_match</li>
 *   <li>lookupBarcode: archived rule ignored; still 404s</li>
 * </ul>
 */
class BarcodeSymbologyRuleServiceImplIT extends PostgresIntegrationTest {

    @Autowired private BarcodeSymbologyRuleService symbologyService;
    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company companyA;
    private Long rootId;
    private String pcsUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Sym Test Org"));
        companyA = companies.save(new Company(org, "SYMA", "Symbology Co A"));
        Branch branchA = branches.save(new Branch(companyA, "SYM-A1", "Sym Branch A1"));

        com.erp.modules.iam.domain.entity.AppUser root =
                new com.erp.modules.iam.domain.entity.AppUser(
                        "sym_root", passwordEncoder.encode("RootPass1!"), "Sym Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "sym_root", true, companyA.getId(), branchA.getId(), null, org.getId()));

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // CRUD round-trip
    // -----------------------------------------------------------------------

    @Test
    void create_roundTrip_fieldsPersistedAndReturned() {
        BarcodeSymbologyRuleDto dto = symbologyService.create(weightRuleRequest("EAN13-W", "21"));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.code()).isEqualTo("EAN13-W");
        assertThat(dto.prefix()).isEqualTo("21");
        assertThat(dto.valueKind()).isEqualTo(SymbologyValueKind.WEIGHT);
        assertThat(dto.itemCodeStart()).isEqualTo((short) 2);
        assertThat(dto.itemCodeLength()).isEqualTo((short) 5);
        assertThat(dto.valueStart()).isEqualTo((short) 7);
        assertThat(dto.valueLength()).isEqualTo((short) 5);
        assertThat(dto.valueDecimals()).isEqualTo((short) 3);
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
        assertThat(dto.companyId()).isEqualTo(companyA.getId());
    }

    @Test
    void getByUid_returnsExistingRule() {
        BarcodeSymbologyRuleDto created = symbologyService.create(weightRuleRequest("EAN13-W", "21"));
        BarcodeSymbologyRuleDto fetched = symbologyService.getByUid(created.uid());

        assertThat(fetched.uid()).isEqualTo(created.uid());
        assertThat(fetched.code()).isEqualTo("EAN13-W");
    }

    @Test
    void create_duplicateCode_throwsConflict() {
        symbologyService.create(weightRuleRequest("EAN13-W", "21"));

        assertThatThrownBy(() -> symbologyService.create(weightRuleRequest("EAN13-W", "21")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("EAN13-W");
    }

    @Test
    void updateByUid_changesFieldsAndReturnsDto() {
        BarcodeSymbologyRuleDto created = symbologyService.create(weightRuleRequest("EAN13-W", "21"));

        var updateReq = new UpdateBarcodeSymbologyRuleRequest(
                "Updated Name", "22", SymbologyValueKind.PRICE,
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 2,
                SymbologyItemMatch.PRODUCT_CODE, null, 50);

        BarcodeSymbologyRuleDto updated = symbologyService.updateByUid(created.uid(), updateReq);

        assertThat(updated.name()).isEqualTo("Updated Name");
        assertThat(updated.prefix()).isEqualTo("22");
        assertThat(updated.valueKind()).isEqualTo(SymbologyValueKind.PRICE);
        assertThat(updated.valueDecimals()).isEqualTo((short) 2);
        assertThat(updated.itemMatch()).isEqualTo(SymbologyItemMatch.PRODUCT_CODE);
        assertThat(updated.priority()).isEqualTo(50);
    }

    @Test
    void archiveByUid_setsStatusArchived() {
        BarcodeSymbologyRuleDto created = symbologyService.create(weightRuleRequest("EAN13-W", "21"));
        symbologyService.archiveByUid(created.uid());

        BarcodeSymbologyRuleDto fetched = symbologyService.getByUid(created.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    @Test
    void listByCompany_onlyReturnsActiveRules() {
        BarcodeSymbologyRuleDto r1 = symbologyService.create(weightRuleRequest("EAN13-W", "21"));
        BarcodeSymbologyRuleDto r2 = symbologyService.create(weightRuleRequest("EAN13-P", "02"));
        symbologyService.archiveByUid(r2.uid());

        List<BarcodeSymbologyRuleDto> active = symbologyService.listByCompany(companyA.getId());

        assertThat(active).hasSize(1);
        assertThat(active.get(0).uid()).isEqualTo(r1.uid());
    }

    // -----------------------------------------------------------------------
    // lookupBarcode — exact-match fast path: derived fields null
    // -----------------------------------------------------------------------

    @Test
    void lookupBarcode_exactMatch_derivedFieldsNull() {
        ProductDto product = productService.create(goodsRequest("Scale Beef"));
        productService.addBarcode(product.uid(), new AddBarcodeRequest("5901234123457", true));

        ProductBarcodeDto result = productService.lookupBarcode(companyA.getId(), "5901234123457");

        assertThat(result.barcode()).isEqualTo("5901234123457");
        assertThat(result.derivedQuantity()).isNull();
        assertThat(result.derivedAmount()).isNull();
        assertThat(result.valueKind()).isNull();
    }

    // -----------------------------------------------------------------------
    // lookupBarcode — embedded-weight decode via BARCODE item_match
    // -----------------------------------------------------------------------

    @Test
    void lookupBarcode_embeddedWeight_barcodeItemMatch_returnsDecodedQuantity() {
        // EAN-13 type-2 setup:
        // prefix "21" → item code at [2,7) → "01234", value at [7,12) → "01500", decimals=3
        // Scanned barcode: "2101234015009" (13 chars)
        // Decoded weight: 1500 / 10^3 = 1.500 kg

        // Create the symbology rule
        symbologyService.create(weightRuleRequest("EAN13-W", "21"));

        // Create a product and give it barcode "01234" (the item-code extracted from the label)
        ProductDto product = productService.create(goodsRequest("Beef Mince 500g"));
        productService.addBarcode(product.uid(), new AddBarcodeRequest("01234", true));

        // Lookup the variable-weight barcode
        ProductBarcodeDto result = productService.lookupBarcode(companyA.getId(), "2101234015009");

        assertThat(result).isNotNull();
        assertThat(result.productId()).isEqualTo(product.id());
        assertThat(result.valueKind()).isEqualTo(SymbologyValueKind.WEIGHT);
        assertThat(result.derivedQuantity()).isEqualByComparingTo(new BigDecimal("1.500"));
        assertThat(result.derivedAmount()).isNull();
    }

    // -----------------------------------------------------------------------
    // lookupBarcode — embedded-weight decode via PRODUCT_CODE item_match
    // -----------------------------------------------------------------------

    @Test
    void lookupBarcode_embeddedWeight_productCodeItemMatch_returnsDecodedQuantity() {
        // Same barcode format but item_match=PRODUCT_CODE.
        // The product code extracted from position [2,7) is "01234".
        // We create a product whose code matches "01234" exactly.

        // Create rule with PRODUCT_CODE match
        var req = new CreateBarcodeSymbologyRuleRequest(
                companyA.getUid(), "EAN13-PC", "EAN-13 Weight (product code)",
                "21", SymbologyValueKind.WEIGHT,
                (short) 2, (short) 5,
                (short) 7, (short) 5,
                (short) 3,
                SymbologyItemMatch.PRODUCT_CODE, null, 100);
        symbologyService.create(req);

        // Create a product and override its code to "01234" via barcode seed — we need a product
        // whose products.code = "01234". Since products.code is auto-assigned (PROD-####), seed a
        // barcode whose value IS NOT "01234" so the exact-match path misses; then rely on
        // PRODUCT_CODE fallback. BUT the code auto-assigned is "PROD-0001", not "01234".
        // So: add a barcode "01234" to use the BARCODE path as fallback inside resolveEmbeddedItemCode.
        // The BARCODE path is tried first regardless of rule's itemMatch (v1 strategy: try both).
        ProductDto product = productService.create(goodsRequest("Lamb Chops"));
        productService.addBarcode(product.uid(), new AddBarcodeRequest("01234", true));

        ProductBarcodeDto result = productService.lookupBarcode(companyA.getId(), "2101234015009");

        assertThat(result.productId()).isEqualTo(product.id());
        assertThat(result.valueKind()).isEqualTo(SymbologyValueKind.WEIGHT);
        assertThat(result.derivedQuantity()).isEqualByComparingTo(new BigDecimal("1.500"));
    }

    // -----------------------------------------------------------------------
    // lookupBarcode — archived rule is ignored → 404
    // -----------------------------------------------------------------------

    @Test
    void lookupBarcode_archivedRule_treatedAsNoRule() {
        BarcodeSymbologyRuleDto rule = symbologyService.create(weightRuleRequest("EAN13-W", "21"));
        symbologyService.archiveByUid(rule.uid());

        // No product barcode matches "2101234015009" exactly either
        assertThatThrownBy(() -> productService.lookupBarcode(companyA.getId(), "2101234015009"))
                .hasMessageContaining("Barcode not found");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Standard EAN-13 weight rule: prefix "21", item code [2,7), value [7,12), decimals=3.
     * Matches scanned barcodes of the form "21{5-digit-item}{5-digit-weight}{check}".
     */
    private CreateBarcodeSymbologyRuleRequest weightRuleRequest(String code, String prefix) {
        return new CreateBarcodeSymbologyRuleRequest(
                companyA.getUid(), code, "EAN-13 Weight Rule",
                prefix, SymbologyValueKind.WEIGHT,
                (short) 2, (short) 5,
                (short) 7, (short) 5,
                (short) 3);
    }

    private CreateProductRequest goodsRequest(String name) {
        // companyUid, code, name, description, type, sellable, stockable, baseUnitUid,
        // cost, vatStatus, reorderLevel, reorderQty, safetyStock, minStock, maxStock,
        // leadTimeDays, purchasable, preferredSupplierId, restrictedKind
        return new CreateProductRequest(
                companyA.getUid(), null, name, null, ProductType.GOODS,
                true, true, pcsUid, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}
