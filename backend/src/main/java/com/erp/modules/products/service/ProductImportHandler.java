package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBarcode;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.repository.ProductBarcodeRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.platform.bulk.BulkImportHandler;
import com.erp.platform.bulk.ColumnSpec;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportParsers;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.MoneyDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk-import handler for products (mass data operations). Maps a spreadsheet row onto the existing
 * {@link ProductService} create/update — so imported products get the same validation, scope check,
 * code generation and audit as hand-entered ones. Upsert by product code: blank code creates (code
 * auto-assigned); a code matching an existing product updates it (blank optional cells keep the
 * current value); a code that doesn't match creates with that code.
 *
 * <p>Optional {@code Barcode} column (column-only per owner decision): when filled it becomes the
 * product's primary barcode. Uniqueness is validated up-front (at the validate step) against the
 * whole company AND against the other rows of the same file, so a conflict is caught before commit.
 *
 * <p>{@code @Transactional}: the framework runs a validate row inside a rolled-back transaction and a
 * commit row in its own — so the loaded product's lazy fields are readable and a failed row rolls
 * back alone.
 */
@Component
@Transactional
public class ProductImportHandler implements BulkImportHandler {

    private static final int EXPORT_MAX = 2000;
    private static final int BARCODE_MAX_LEN = 64;

    private static final String COL_CODE = "Code";
    private static final String COL_NAME = "Name";
    private static final String COL_DESCRIPTION = "Description";
    private static final String COL_TYPE = "Type";
    private static final String COL_SELLABLE = "Sellable";
    private static final String COL_STOCKABLE = "Stockable";
    private static final String COL_BASE_UNIT = "Base Unit";
    private static final String COL_COST_AMOUNT = "Cost Amount";
    private static final String COL_COST_CURRENCY = "Cost Currency";
    private static final String COL_VAT = "VAT Status";
    private static final String COL_REORDER_LEVEL = "Reorder Level";
    private static final String COL_REORDER_QTY = "Reorder Qty";
    private static final String COL_LEAD_TIME = "Lead Time Days";
    private static final String COL_PURCHASABLE = "Purchasable";
    private static final String COL_BARCODE = "Barcode";

    private final ProductService productService;
    private final ProductRepository products;
    private final ProductBarcodeRepository barcodes;
    private final UnitOfMeasureRepository units;
    private final CompanyRepository companies;

    public ProductImportHandler(ProductService productService,
                                ProductRepository products,
                                ProductBarcodeRepository barcodes,
                                UnitOfMeasureRepository units,
                                CompanyRepository companies) {
        this.productService = productService;
        this.products = products;
        this.barcodes = barcodes;
        this.units = units;
        this.companies = companies;
    }

    @Override
    public String key() {
        return "products";
    }

    @Override
    public String displayName() {
        return "Products";
    }

    @Override
    public String permissionCode() {
        return "PRODUCT.IMPORT";
    }

    @Override
    public List<ColumnSpec> columns(Long companyId) {
        List<String> yesNo = List.of("Yes", "No");
        List<String> unitCodes = units.findByCompanyId(companyId).stream()
                .map(u -> u.getCode())
                .sorted()
                .toList();
        ColumnSpec baseUnit = unitCodes.isEmpty()
                ? ColumnSpec.of(COL_BASE_UNIT, true, "Existing unit-of-measure code, e.g. PCS, KG.")
                : ColumnSpec.choice(COL_BASE_UNIT, true, "Existing unit-of-measure code.", unitCodes);
        return List.of(
                ColumnSpec.of(COL_CODE, false,
                        "Leave blank to auto-generate (PROD-####). Fill in to update an existing product."),
                ColumnSpec.of(COL_NAME, true, "Product name."),
                ColumnSpec.of(COL_DESCRIPTION, false, "Optional description."),
                ColumnSpec.choice(COL_TYPE, true, "GOODS is stockable; SERVICE is not.",
                        List.of(ProductType.GOODS.name(), ProductType.SERVICE.name())),
                ColumnSpec.choice(COL_SELLABLE, true, "Can this be sold?", yesNo),
                ColumnSpec.choice(COL_STOCKABLE, true, "Is stock tracked? (Must be No for a SERVICE.)", yesNo),
                baseUnit,
                ColumnSpec.number(COL_COST_AMOUNT, false, "Unit cost. Requires a currency when set."),
                ColumnSpec.of(COL_COST_CURRENCY, false, "3-letter code, e.g. TZS."),
                ColumnSpec.choice(COL_VAT, false, "Defaults to STANDARD when blank.",
                        List.of(VatStatus.STANDARD.name(), VatStatus.ZERO_RATED.name(),
                                VatStatus.EXEMPT.name())),
                ColumnSpec.number(COL_REORDER_LEVEL, false, "Optional planning field."),
                ColumnSpec.number(COL_REORDER_QTY, false, "Optional planning field."),
                ColumnSpec.number(COL_LEAD_TIME, false, "Optional supply lead time in days."),
                ColumnSpec.choice(COL_PURCHASABLE, false, "Can this be bought?", yesNo),
                ColumnSpec.of(COL_BARCODE, false,
                        "Primary barcode (scannable). Must be unique across products; leave blank for none."));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        String code = row.get(COL_CODE);
        Product existing = code.isEmpty()
                ? null
                : products.findByCompanyIdAndCode(companyId, code.trim().toUpperCase()).orElse(null);

        String barcode = ImportParsers.text(row, COL_BARCODE);
        validateBarcode(companyId, barcode, existing, ctx);

        if (existing != null) {
            productService.updateByUid(existing.getUid(), buildUpdate(companyId, existing, row));
            applyBarcodeToExisting(existing, barcode);
            return RowOutcome.update(row.rowNumber(), existing.getCode());
        }

        ProductDto created = productService.create(buildCreate(companyId, code, row));
        if (barcode != null) {
            productService.addBarcode(created.uid(), new AddBarcodeRequest(barcode, true));
        }
        return RowOutcome.create(row.rowNumber(), created.code());
    }

    // -- barcode ------------------------------------------------------------------------------------

    /**
     * Rejects a barcode that would collide — either with a DIFFERENT product already in the company,
     * or with another row earlier in the same file. Runs in BOTH validate and commit, so conflicts
     * surface before anything is written.
     */
    private void validateBarcode(Long companyId, String barcode, Product existing, ImportContext ctx) {
        if (barcode == null) {
            return;
        }
        if (barcode.length() > BARCODE_MAX_LEN) {
            throw new IllegalArgumentException(
                    "'" + COL_BARCODE + "' is too long (max " + BARCODE_MAX_LEN + " characters).");
        }
        if (!ctx.claimSet("barcode").add(barcode)) {
            throw new IllegalArgumentException(
                    "'" + COL_BARCODE + "' '" + barcode + "' is used more than once in this file.");
        }
        barcodes.findByCompanyIdAndBarcode(companyId, barcode).ifPresent(b -> {
            boolean sameProduct = existing != null && b.getProduct().getId().equals(existing.getId());
            if (!sameProduct) {
                throw new IllegalArgumentException("'" + COL_BARCODE + "' '" + barcode
                        + "' is already used by product " + b.getProduct().getCode() + ".");
            }
        });
    }

    /** Add the barcode to an existing product unless it already has it; primary only if it has none. */
    private void applyBarcodeToExisting(Product product, String barcode) {
        if (barcode == null) {
            return;
        }
        List<ProductBarcode> current = barcodes.findByProductId(product.getId());
        if (current.stream().anyMatch(b -> b.getBarcode().equals(barcode))) {
            return; // already this product's barcode — idempotent
        }
        productService.addBarcode(product.getUid(), new AddBarcodeRequest(barcode, current.isEmpty()));
    }

    // -- request building ---------------------------------------------------------------------------

    private CreateProductRequest buildCreate(Long companyId, String code, ImportRow row) {
        return new CreateProductRequest(
                resolveCompanyUid(companyId),
                code.isEmpty() ? null : code,
                ImportParsers.requireText(row, COL_NAME),
                row.get(COL_DESCRIPTION),
                ImportParsers.parseEnumRequired(ProductType.class, row, COL_TYPE),
                ImportParsers.parseBoolRequired(row, COL_SELLABLE),
                ImportParsers.parseBoolRequired(row, COL_STOCKABLE),
                resolveUnitUid(companyId, ImportParsers.requireText(row, COL_BASE_UNIT)),
                money(row, COL_COST_AMOUNT, COL_COST_CURRENCY),
                ImportParsers.parseEnum(VatStatus.class, row, COL_VAT, null),
                ImportParsers.parseDecimal(row, COL_REORDER_LEVEL),
                ImportParsers.parseDecimal(row, COL_REORDER_QTY),
                null, null, null,
                ImportParsers.parseInt(row, COL_LEAD_TIME),
                ImportParsers.parseBool(row, COL_PURCHASABLE, null),
                null,
                null);
    }

    private UpdateProductRequest buildUpdate(Long companyId, Product existing, ImportRow row) {
        ProductDto cur = ProductDto.from(existing);
        return new UpdateProductRequest(
                row.has(COL_NAME) ? row.get(COL_NAME) : cur.name(),
                row.has(COL_DESCRIPTION) ? row.get(COL_DESCRIPTION) : cur.description(),
                row.has(COL_TYPE) ? ImportParsers.parseEnumRequired(ProductType.class, row, COL_TYPE) : cur.type(),
                row.has(COL_SELLABLE) ? ImportParsers.parseBoolRequired(row, COL_SELLABLE) : cur.sellable(),
                row.has(COL_STOCKABLE) ? ImportParsers.parseBoolRequired(row, COL_STOCKABLE) : cur.stockable(),
                row.has(COL_BASE_UNIT) ? resolveUnitUid(companyId, row.get(COL_BASE_UNIT)) : cur.baseUnitUid(),
                row.has(COL_COST_AMOUNT) ? money(row, COL_COST_AMOUNT, COL_COST_CURRENCY) : cur.cost(),
                row.has(COL_VAT) ? ImportParsers.parseEnum(VatStatus.class, row, COL_VAT, cur.vatStatus()) : cur.vatStatus(),
                row.has(COL_REORDER_LEVEL) ? ImportParsers.parseDecimal(row, COL_REORDER_LEVEL) : cur.reorderLevel(),
                row.has(COL_REORDER_QTY) ? ImportParsers.parseDecimal(row, COL_REORDER_QTY) : cur.reorderQty(),
                cur.safetyStock(),
                cur.minStock(),
                cur.maxStock(),
                row.has(COL_LEAD_TIME) ? ImportParsers.parseInt(row, COL_LEAD_TIME) : cur.leadTimeDays(),
                row.has(COL_PURCHASABLE) ? ImportParsers.parseBool(row, COL_PURCHASABLE, cur.purchasable()) : cur.purchasable(),
                cur.preferredSupplierId(),
                cur.restrictedKind());
    }

    // -- export -------------------------------------------------------------------------------------

    @Override
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        Map<Long, String> primaryBarcodes = new HashMap<>();
        for (ProductBarcode b : barcodes.findByCompanyIdAndPrimaryTrue(companyId)) {
            primaryBarcodes.put(b.getProduct().getId(), b.getBarcode());
        }
        List<LinkedHashMap<String, String>> rows = new ArrayList<>();
        for (Product p : products.findByCompanyId(companyId, Pageable.unpaged()).getContent()) {
            if (p.getStatus() != MasterStatus.ACTIVE) {
                continue;
            }
            ProductDto d = ProductDto.from(p);
            LinkedHashMap<String, String> r = new LinkedHashMap<>();
            r.put(COL_CODE, nz(d.code()));
            r.put(COL_NAME, nz(d.name()));
            r.put(COL_DESCRIPTION, nz(d.description()));
            r.put(COL_TYPE, d.type() != null ? d.type().name() : "");
            r.put(COL_SELLABLE, yn(d.sellable()));
            r.put(COL_STOCKABLE, yn(d.stockable()));
            r.put(COL_BASE_UNIT, nz(d.baseUnitCode()));
            r.put(COL_COST_AMOUNT, d.cost() != null ? d.cost().amount() : "");
            r.put(COL_COST_CURRENCY, d.cost() != null ? d.cost().currency() : "");
            r.put(COL_VAT, d.vatStatus() != null ? d.vatStatus().name() : "");
            r.put(COL_REORDER_LEVEL, dec(d.reorderLevel()));
            r.put(COL_REORDER_QTY, dec(d.reorderQty()));
            r.put(COL_LEAD_TIME, d.leadTimeDays() != null ? d.leadTimeDays().toString() : "");
            r.put(COL_PURCHASABLE, yn(d.purchasable()));
            r.put(COL_BARCODE, nz(primaryBarcodes.get(p.getId())));
            rows.add(r);
            if (rows.size() >= EXPORT_MAX) {
                break;
            }
        }
        return rows;
    }

    // -- helpers ------------------------------------------------------------------------------------

    private String resolveCompanyUid(Long companyId) {
        return companies.findScopedById(companyId)
                .map(c -> c.getUid())
                .orElseThrow(() -> new IllegalStateException("Active company not found."));
    }

    private String resolveUnitUid(Long companyId, String code) {
        String c = code.trim();
        return units.findByCompanyIdAndCode(companyId, c)
                .or(() -> units.findByCompanyIdAndCode(companyId, c.toUpperCase()))
                .map(u -> u.getUid())
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_BASE_UNIT + "' '" + code + "' is not a known unit-of-measure code."));
    }

    private static MoneyDto money(ImportRow row, String amountCol, String currencyCol) {
        BigDecimal amount = ImportParsers.parseDecimal(row, amountCol);
        if (amount == null) {
            return null;
        }
        String currency = row.get(currencyCol);
        if (currency.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + currencyCol + "' is required when '" + amountCol + "' is set.");
        }
        return new MoneyDto(amount.toPlainString(), currency.toUpperCase());
    }

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String yn(boolean b) {
        return b ? "Yes" : "No";
    }

    private static String dec(BigDecimal d) {
        return d == null ? "" : d.toPlainString();
    }
}
