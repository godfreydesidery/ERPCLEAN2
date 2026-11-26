package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductBranchDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetBranchMenuRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.entity.ProductBranch;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.bulk.BulkImportService;
import com.erp.platform.bulk.ColumnSpec;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportReport;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowAction;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The "Branch menu &amp; prices" bulk sheet (ADR-0073) against real Postgres: blank = unchanged, only
 * real differences are written, the D-1 rules hold through the sheet, a bad cell rejects the whole
 * row, and an untouched download re-uploaded changes nothing.
 */
class BranchMenuImportHandlerIT extends PostgresIntegrationTest {

    @Autowired private BranchMenuImportHandler handler;
    @Autowired private BulkImportService bulk;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private BranchMenuService branchMenu;
    @Autowired private ProductBranchRepository productBranches;
    @Autowired private ProductRepository productRepo;
    @Autowired private AuditRepository auditRepository;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company company;
    private Long companyId;
    private Branch arusha;
    private Branch moshi;
    private Long rootId;
    private UnitOfMeasureDto cup;
    private ProductDto latte;      // no rows: sold everywhere
    private ProductDto mandazi;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        org = organisations.save(new Organisation("Menu Sheet Org"));
        company = companies.save(new Company(org, "MSHT", "Menu Sheet Co"));
        companyId = company.getId();
        arusha = branches.save(new Branch(company, "MS-ARU", "Arusha"));
        moshi = branches.save(new Branch(company, "MS-MSH", "Moshi"));

        AppUser root = new AppUser("menusheet_root", passwordEncoder.encode("RootPass1!"), "Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        at(arusha);

        cup = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "CUP", "Cup"));
        latte = product("Cafe Latte");
        mandazi = product("Mandazi");
        PriceListDto retail = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail"));
        priceListService.setDefaultByUid(retail.uid());
        productService.setPrice(latte.uid(),
                new SetProductPriceRequest(retail.uid(), new MoneyDto("8000.00", "TZS"), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---- columns ------------------------------------------------------------------

    @Test
    void columns_areASoldAndAPriceColumnPerActiveBranch() {
        assertThat(handler.columns(companyId)).extracting(ColumnSpec::header).containsExactly(
                "Product Code", "Product Name", "Company Price",
                "Sold here: Arusha [MS-ARU]", "Price here: Arusha [MS-ARU]",
                "Sold here: Moshi [MS-MSH]", "Price here: Moshi [MS-MSH]");
    }

    // ---- blank = unchanged ----------------------------------------------------------

    @Test
    void aRowWithOnlyTheCode_changesNothing() {
        RowOutcome o = commit(row("Product Code", latte.code()));

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
        assertThat(productBranches.findByProductId(latte.id())).isEmpty();
    }

    @Test
    void aCellAlreadyAsAsked_writesNothing_andRecordsNothing() {
        long auditBefore = auditRepository.count();

        // Latte has no rows, so it is already sold at both branches.
        RowOutcome o = commit(row("Product Code", latte.code(),
                "Sold here: Arusha [MS-ARU]", "Yes", "Sold here: Moshi [MS-MSH]", "Yes"));

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
        assertThat(productBranches.findByProductId(latte.id())).isEmpty();
        assertThat(auditRepository.count()).isEqualTo(auditBefore);
    }

    // ---- the ADR-0073 D-1 rules, through the sheet -----------------------------------

    @Test
    void soldNo_onAnItemSoldEverywhere_hidesItOnlyThere() {
        RowOutcome o = commit(row("Product Code", mandazi.code(), "Sold here: Moshi [MS-MSH]", "No"));

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(o.reference()).contains("Moshi: no longer sold");
        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isFalse();
        assertThat(branchMenu.isSoldAt(mandazi.id(), arusha.getId())).isTrue();
    }

    @Test
    void soldYes_atANewBranch_forAnItemRestrictedElsewhere_startsSellingItThere() {
        arushaOnly(mandazi);   // what a branch opened later looks like: the item is hidden there

        commit(row("Product Code", mandazi.code(), "Sold here: Moshi [MS-MSH]", "Yes"));

        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(mandazi.id(), arusha.getId())).isTrue();
    }

    @Test
    void aPriceAlone_atABranchThatDoesNotSellTheItem_doesNotStartSellingIt_andSaysSo() {
        arushaOnly(mandazi);

        RowOutcome o = commit(row("Product Code", mandazi.code(), "Price here: Moshi [MS-MSH]", "600"));

        assertThat(o.reference()).contains("price 600").contains("not sold there");
        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isFalse();
    }

    @Test
    void branchPrice_isSet_thenChanged_thenCleared() {
        commit(row("Product Code", latte.code(), "Price here: Moshi [MS-MSH]", "9000"));
        assertThat(priceAt(latte, moshi)).isEqualByComparingTo("9000");
        // Setting a price on an item with no rows kept it on every branch.
        assertThat(branchMenu.isSoldAt(latte.id(), arusha.getId())).isTrue();

        RowOutcome changed = commit(row("Product Code", latte.code(), "Price here: Moshi [MS-MSH]", "9,500"));
        assertThat(changed.reference()).contains("price 9000 → 9500");
        assertThat(priceAt(latte, moshi)).isEqualByComparingTo("9500");

        RowOutcome cleared = commit(row("Product Code", latte.code(), "Price here: Moshi [MS-MSH]", "clear"));
        assertThat(cleared.reference()).contains("branch price removed");
        assertThat(rowAt(latte, moshi).map(ProductBranchDto::branchPrice)).isEmpty();
    }

    @Test
    void clear_onABranchWithNoPrice_isANoOp() {
        RowOutcome o = commit(row("Product Code", latte.code(), "Price here: Moshi [MS-MSH]", "CLEAR"));

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
        assertThat(productBranches.findByProductId(latte.id())).isEmpty();
    }

    // ---- a bad row fails whole and alone ---------------------------------------------

    @Test
    void aBadCell_rejectsTheWholeRow_beforeAnythingIsWritten() {
        assertThatThrownBy(() -> commit(row("Product Code", latte.code(),
                "Sold here: Arusha [MS-ARU]", "No",              // valid…
                "Price here: Moshi [MS-MSH]", "cheap")))          // …but this is not
                .hasMessageContaining("must be a price, blank, or CLEAR");
        assertThat(productBranches.findByProductId(latte.id())).isEmpty();
    }

    @Test
    void negativePrice_unknownYesNo_unknownItem_andADuplicateRow_areRejected() {
        assertThatThrownBy(() -> commit(row("Product Code", latte.code(),
                "Price here: Moshi [MS-MSH]", "-5"))).hasMessageContaining("cannot be negative");
        assertThatThrownBy(() -> commit(row("Product Code", latte.code(),
                "Sold here: Moshi [MS-MSH]", "maybe"))).hasMessageContaining("Yes or No");
        assertThatThrownBy(() -> commit(row("Product Code", "NOPE-1",
                "Sold here: Moshi [MS-MSH]", "No"))).hasMessageContaining("was not found");

        ImportContext ctx = new ImportContext();
        handler.process(companyId, row("Product Code", latte.code()), ImportMode.COMMIT, ctx);
        assertThatThrownBy(() -> handler.process(companyId, row("Product Code", latte.code()),
                ImportMode.COMMIT, ctx)).hasMessageContaining("more than once");
    }

    @Test
    void anotherCompanysItem_isNotFound() {
        Company other = companies.save(new Company(org, "MSH2", "Other Co"));
        branches.save(new Branch(other, "MS2-A", "Other Branch"));
        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(other.getUid(), "PCS", "Pieces"));
        ProductDto theirs = productService.create(new CreateProductRequest(
                other.getUid(), "THEIRS-1", "Their Item", null, ProductType.GOODS, true, false,
                pcs.uid(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));

        assertThatThrownBy(() -> commit(row("Product Code", theirs.code(),
                "Sold here: Moshi [MS-MSH]", "No"))).hasMessageContaining("was not found");
        assertThat(productBranches.findByProductId(theirs.id())).isEmpty();
    }

    // ---- the real file round trip -----------------------------------------------------

    @Test
    void export_showsTheCurrentMenuAndPrices() {
        productService.setBranchMenu(mandazi.uid(),
                new SetBranchMenuRequest(moshi.getUid(), false, null, null));
        productService.setBranchMenu(latte.uid(),
                new SetBranchMenuRequest(arusha.getUid(), null, new BigDecimal("9000"), null));

        Map<String, LinkedHashMap<String, String>> byCode = new LinkedHashMap<>();
        handler.exportRows(companyId, Map.of()).forEach(r -> byCode.put(r.get("Product Code"), r));

        assertThat(byCode.get(latte.code()))
                .containsEntry("Company Price", "8000")
                .containsEntry("Sold here: Arusha [MS-ARU]", "Yes")
                .containsEntry("Price here: Arusha [MS-ARU]", "9000")
                .containsEntry("Sold here: Moshi [MS-MSH]", "Yes")
                .containsEntry("Price here: Moshi [MS-MSH]", "");
        assertThat(byCode.get(mandazi.code()))
                .containsEntry("Sold here: Arusha [MS-ARU]", "Yes")
                .containsEntry("Sold here: Moshi [MS-MSH]", "No");
    }

    @Test
    void anUntouchedDownload_reuploaded_changesNothing() throws Exception {
        productService.setBranchMenu(mandazi.uid(),
                new SetBranchMenuRequest(moshi.getUid(), false, null, null));
        productService.setBranchMenu(latte.uid(),
                new SetBranchMenuRequest(arusha.getUid(), null, new BigDecimal("9000"), null));
        List<ProductBranch> before = productBranches.findAll();
        long auditBefore = auditRepository.count();

        ImportReport report = bulk.run("branch-menu",
                xlsx(bulk.export("branch-menu", Map.of())), ImportMode.COMMIT);

        assertThat(report.errors()).isZero();
        assertThat(report.updated()).isZero();
        assertThat(report.skipped()).isEqualTo(report.total()).isEqualTo(2);
        assertThat(productBranches.findAll()).hasSameSizeAs(before);
        assertThat(auditRepository.count()).isEqualTo(auditBefore);
    }

    @Test
    void anEditedDownload_validatesWithoutWriting_thenCommits() throws Exception {
        byte[] edited = editCell(bulk.export("branch-menu", Map.of()),
                mandazi.code(), "Sold here: Moshi [MS-MSH]", "No");

        ImportReport check = bulk.run("branch-menu", xlsx(edited), ImportMode.VALIDATE);
        assertThat(check.errors()).as(check.rows().toString()).isZero();
        assertThat(check.updated()).as(check.rows().toString()).isEqualTo(1);
        assertThat(check.rows()).anySatisfy(r ->
                assertThat(r.reference()).contains("Moshi: no longer sold"));
        // The check wrote nothing.
        assertThat(productBranches.findByProductId(mandazi.id())).isEmpty();

        ImportReport done = bulk.run("branch-menu", xlsx(edited), ImportMode.COMMIT);
        assertThat(done.updated()).isEqualTo(1);
        assertThat(branchMenu.isSoldAt(mandazi.id(), moshi.getId())).isFalse();
        assertThat(branchMenu.isSoldAt(mandazi.id(), arusha.getId())).isTrue();
        assertThat(branchMenu.isSoldAt(latte.id(), moshi.getId())).isTrue();
    }

    // ---- helpers ------------------------------------------------------------------

    private RowOutcome commit(ImportRow row) {
        return handler.process(companyId, row, ImportMode.COMMIT, new ImportContext());
    }

    private Optional<ProductBranchDto> rowAt(ProductDto item, Branch branch) {
        return productService.listBranches(item.uid()).stream()
                .filter(r -> r.branchId().equals(branch.getId())).findFirst();
    }

    private BigDecimal priceAt(ProductDto item, Branch branch) {
        return rowAt(item, branch).map(ProductBranchDto::branchPrice).orElseThrow();
    }

    /** A row for Arusha and none for Moshi — an item restricted before Moshi existed. */
    private void arushaOnly(ProductDto item) {
        var product = productRepo.findByCompanyIdAndUid(companyId, item.uid()).orElseThrow();
        productBranches.save(new ProductBranch(product, arusha.getId(), rootId));
    }

    private void at(Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "menusheet_root", true, companyId, branch.getId(), null, org.getId()));
    }

    private ProductDto product(String name) {
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null, ProductType.GOODS, true, false, cup.uid(),
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null));
    }

    private static ImportRow row(String... kv) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            cells.put(kv[i], kv[i + 1]);
        }
        return new ImportRow(2, cells);
    }

    private static MockMultipartFile xlsx(byte[] bytes) {
        return new MockMultipartFile("file", "branch-menu.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);
    }

    /** Set one cell of the Data sheet, as a user editing the download in Excel would. */
    private static byte[] editCell(byte[] xlsx, String productCode, String header, String value)
            throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet data = wb.getSheet("Data");
            Row head = data.getRow(0);
            int col = -1;
            for (Cell c : head) {
                if (c.getStringCellValue().replace("*", "").trim().equals(header)) {
                    col = c.getColumnIndex();
                }
            }
            assertThat(col).as("column " + header).isNotNegative();
            boolean edited = false;
            StringBuilder seen = new StringBuilder();
            for (int r = 1; r <= data.getLastRowNum(); r++) {
                Row row = data.getRow(r);
                if (row == null || row.getCell(0) == null) {
                    continue;
                }
                seen.append('[').append(row.getCell(0)).append(']');
                if (productCode.equals(row.getCell(0).getStringCellValue())) {
                    // Replace, don't set: the export writes inline strings, and POI's in-place
                    // setCellValue on an inline-string cell leaves the old text in the file.
                    // Excel itself rewrites the cell properly, so this is a test-helper concern.
                    Cell old = row.getCell(col);
                    if (old != null) {
                        row.removeCell(old);
                    }
                    row.createCell(col).setCellValue(value);
                    edited = true;
                }
            }
            assertThat(edited).as("row " + productCode + " among " + seen + " col " + col).isTrue();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }
}
