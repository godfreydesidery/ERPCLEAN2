package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
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
import com.erp.platform.security.PermissionChecks;
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
 * Bulk PRICE update — one sheet that updates a product's <b>cost</b> (on the product) and/or its
 * <b>selling price</b> on a named price list. Each field routes through the existing service call
 * ({@link ProductService#updateByUid} for cost, {@link ProductService#setPrice} for the selling
 * price) so every change gets the same validation, scope check and audit as the single screens.
 * Products, price lists and units are addressed by their human codes.
 *
 * <p><b>Download → edit → re-upload:</b> {@link #exportRows} emits one row per product for a chosen
 * price list, pre-filled with the current cost and selling price. A <b>blank Cost / Selling Price is
 * left unchanged</b>, and a cost that equals the current value is a no-op — so you can download every
 * product, edit only the cells you want, and upload the whole sheet.
 *
 * <p><b>Cost is permission-gated:</b> the sheet endpoint requires {@code PRICE.MASS_UPDATE}, but
 * actually <i>changing</i> a cost additionally requires {@code PRODUCT.MANAGE}/{@code PRODUCT.IMPORT}
 * — a price-only user can update selling prices but not costs. The gate only trips when a cost value
 * differs from the current one, so re-uploading a pre-filled sheet never falsely blocks a price edit.
 *
 * <p>For an across-the-board change (e.g. "raise RETAIL by 5%") use the rule-based mass price change
 * instead — this handler is for arbitrary per-product edits.
 */
@Component
@Transactional
public class PriceImportHandler implements BulkImportHandler {

    private static final int EXPORT_MAX = 2000;
    private static final String DEFAULT_CURRENCY = "TZS";

    private static final String COL_PRODUCT = "Product Code";
    private static final String COL_NAME = "Product Name";
    private static final String COL_COST = "Cost Amount";
    private static final String COL_COST_CCY = "Cost Currency";
    private static final String COL_PRICE_LIST = "Price List";
    private static final String COL_UNIT = "Unit";
    private static final String COL_SELLING = "Selling Price";
    private static final String COL_SELLING_CCY = "Selling Currency";

    private final ProductService productService;
    private final ProductRepository products;
    private final PriceListRepository priceLists;
    private final ProductPriceRepository prices;
    private final UnitOfMeasureRepository units;
    private final CompanyRepository companies;
    private final PermissionChecks perm;

    public PriceImportHandler(ProductService productService,
                              ProductRepository products,
                              PriceListRepository priceLists,
                              ProductPriceRepository prices,
                              UnitOfMeasureRepository units,
                              CompanyRepository companies,
                              PermissionChecks perm) {
        this.productService = productService;
        this.products = products;
        this.priceLists = priceLists;
        this.prices = prices;
        this.units = units;
        this.companies = companies;
        this.perm = perm;
    }

    @Override
    public String key() {
        return "prices";
    }

    @Override
    public String displayName() {
        return "Product prices & cost";
    }

    @Override
    public String permissionCode() {
        return "PRICE.MASS_UPDATE";
    }

    @Override
    public List<ColumnSpec> columns(Long companyId) {
        List<String> priceListCodes = priceLists.findByCompanyId(companyId, Pageable.unpaged())
                .getContent().stream()
                .map(PriceList::getCode)
                .sorted()
                .toList();
        ColumnSpec priceList = priceListCodes.isEmpty()
                ? ColumnSpec.of(COL_PRICE_LIST, false,
                        "Existing price-list code, e.g. RETAIL. Required when Selling Price is set.")
                : ColumnSpec.choice(COL_PRICE_LIST, false,
                        "Existing price-list code. Required when Selling Price is set.", priceListCodes);
        return List.of(
                ColumnSpec.of(COL_PRODUCT, true, "Existing product code."),
                ColumnSpec.reference(COL_NAME, "The product's name — to identify the row."),
                ColumnSpec.number(COL_COST, false,
                        "Unit COST. Fill to update it (needs product-management permission). Blank or "
                      + "unchanged = left as-is."),
                ColumnSpec.of(COL_COST_CCY, false,
                        "Cost currency (3-letter, e.g. TZS). Blank = the company base currency."),
                priceList,
                ColumnSpec.of(COL_UNIT, false,
                        "Blank = base-unit price. A unit code sets that pack's price (the pack must "
                      + "already be configured on the product)."),
                ColumnSpec.number(COL_SELLING, false,
                        "The SELLING price on the price list above. Blank = LEAVE UNCHANGED."),
                ColumnSpec.of(COL_SELLING_CCY, false,
                        "Selling currency (3-letter, e.g. TZS). Required when Selling Price is set."));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        BigDecimal costAmount = ImportParsers.parseDecimal(row, COL_COST);
        BigDecimal sellingAmount = ImportParsers.parseDecimal(row, COL_SELLING);
        if (costAmount == null && sellingAmount == null) {
            return RowOutcome.skip(row.rowNumber(), row.get(COL_PRODUCT),
                    "No Cost or Selling Price entered — left unchanged.");
        }

        String productCode = ImportParsers.requireText(row, COL_PRODUCT).trim().toUpperCase();
        Product product = products.findByCompanyIdAndCode(companyId, productCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_PRODUCT + "' '" + productCode + "' was not found."));

        List<String> changes = new ArrayList<>(2);
        applyCost(companyId, row, product, costAmount, changes);
        applySellingPrice(companyId, row, product, sellingAmount, changes);

        if (changes.isEmpty()) {
            return RowOutcome.skip(row.rowNumber(), productCode,
                    "No change — the values match the current record.");
        }
        return RowOutcome.update(row.rowNumber(), productCode + " (" + String.join(", ", changes) + ")");
    }

    /** Update the product's cost, but only when it actually differs — and only with product permission. */
    private void applyCost(Long companyId, ImportRow row, Product product,
                           BigDecimal costAmount, List<String> changes) {
        if (costAmount == null) {
            return;
        }
        String costCcy = row.has(COL_COST_CCY)
                ? row.get(COL_COST_CCY).trim().toUpperCase()
                : baseCurrency(companyId);
        if (!costChanged(product, costAmount, costCcy)) {
            return; // same as current — leave as-is, no permission needed
        }
        if (!perm.has("PRODUCT.MANAGE") && !perm.has("PRODUCT.IMPORT")) {
            throw new IllegalArgumentException(
                    "Changing '" + COL_COST + "' needs product-management permission, which you do not "
                  + "have. Clear the Cost column to update only the selling price.");
        }
        ProductDto cur = ProductDto.from(product);
        productService.updateByUid(product.getUid(), new UpdateProductRequest(
                cur.name(), cur.description(), cur.type(), cur.sellable(), cur.stockable(),
                cur.baseUnitUid(), new MoneyDto(costAmount.toPlainString(), costCcy), cur.vatStatus(),
                cur.reorderLevel(), cur.reorderQty(), cur.safetyStock(), cur.minStock(), cur.maxStock(),
                cur.leadTimeDays(), cur.purchasable(), cur.preferredSupplierId(), cur.restrictedKind()));
        changes.add("cost");
    }

    /** Update the product's selling price on the named price list (a blank Selling Price is skipped). */
    private void applySellingPrice(Long companyId, ImportRow row, Product product,
                                   BigDecimal sellingAmount, List<String> changes) {
        if (sellingAmount == null) {
            return;
        }
        String plCode = row.get(COL_PRICE_LIST).trim();
        if (plCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + COL_PRICE_LIST + "' is required when '" + COL_SELLING + "' is set.");
        }
        PriceList priceList = findPriceList(companyId, plCode);
        String sellingCcy = row.get(COL_SELLING_CCY).trim().toUpperCase();
        if (sellingCcy.isEmpty()) {
            throw new IllegalArgumentException(
                    "'" + COL_SELLING_CCY + "' is required when '" + COL_SELLING + "' is set.");
        }
        String unitUid = row.has(COL_UNIT) ? resolveUnitUid(companyId, row.get(COL_UNIT)) : null;
        productService.setPrice(product.getUid(), new SetProductPriceRequest(
                priceList.getUid(), new MoneyDto(sellingAmount.toPlainString(), sellingCcy), unitUid));
        changes.add("price @ " + priceList.getCode());
    }

    /** True when the supplied cost differs from the product's current cost (amount or currency). */
    private static boolean costChanged(Product p, BigDecimal newAmount, String newCcy) {
        var cost = p.getCost();
        if (cost == null || cost.getAmount() == null) {
            return true;
        }
        boolean sameAmount = cost.getAmount().compareTo(newAmount) == 0;
        boolean sameCcy = cost.getCurrency() != null
                && cost.getCurrency().value().equalsIgnoreCase(newCcy);
        return !(sameAmount && sameCcy);
    }

    @Override
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        PriceList priceList = resolveExportPriceList(companyId, params);
        if (priceList == null) {
            return List.of();
        }
        String listCurrency = priceList.getCurrency() != null ? priceList.getCurrency().value() : "";
        String baseCcy = baseCurrency(companyId);

        // Current base-unit prices on this list, keyed by product id (one query, no N+1).
        Map<Long, ProductPrice> baseByProduct = new HashMap<>();
        for (ProductPrice pp : prices.findByCompanyIdAndPriceListId(companyId, priceList.getId())) {
            if (pp.getUnit() == null) {
                baseByProduct.put(pp.getProduct().getId(), pp);
            }
        }

        return products.findByCompanyId(companyId, Pageable.unpaged()).getContent().stream()
                .filter(p -> p.getStatus() == MasterStatus.ACTIVE)
                .limit(EXPORT_MAX)
                .map(p -> exportRow(p, priceList, baseByProduct.get(p.getId()), listCurrency, baseCcy))
                .toList();
    }

    /** One export row: the current cost and selling price, plus the Name reference. */
    private static LinkedHashMap<String, String> exportRow(Product p, PriceList priceList,
                                                           ProductPrice pp, String listCurrency,
                                                           String baseCcy) {
        boolean priced = pp != null && pp.getPrice() != null;
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put(COL_PRODUCT, p.getCode());
        r.put(COL_NAME, p.getName());
        r.put(COL_COST, costAmountOf(p));
        r.put(COL_COST_CCY, costCurrencyOf(p, baseCcy));
        r.put(COL_PRICE_LIST, priceList.getCode());
        r.put(COL_UNIT, "");
        r.put(COL_SELLING, priced ? pp.getPrice().getAmount().toPlainString() : "");
        r.put(COL_SELLING_CCY, priced ? pp.getPrice().getCurrency().value() : listCurrency);
        return r;
    }

    private static String costAmountOf(Product p) {
        return p.getCost() != null && p.getCost().getAmount() != null
                ? p.getCost().getAmount().toPlainString() : "";
    }

    private static String costCurrencyOf(Product p, String baseCcy) {
        if (p.getCost() != null && p.getCost().getCurrency() != null) {
            return p.getCost().getCurrency().value();
        }
        return baseCcy;
    }

    /** The company base currency (for the Cost Currency default); falls back to TZS. */
    private String baseCurrency(Long companyId) {
        return companies.findScopedById(companyId)
                // Lambda (not Company::getBaseCurrency) so we never import the iam Company entity —
                // that would cross the module boundary (ModuleBoundaryTest). Mirrors ProductImportHandler.
                .map(c -> c.getBaseCurrency())
                .filter(s -> s != null && !s.isBlank())
                .orElse(DEFAULT_CURRENCY);
    }

    /** The price list to export prices for: the {@code priceList} param code, else the default, else the first. */
    private PriceList resolveExportPriceList(Long companyId, Map<String, String> params) {
        String code = params == null ? null : params.get("priceList");
        if (code != null && !code.isBlank()) {
            return findPriceList(companyId, code);
        }
        List<PriceList> all = priceLists.findByCompanyId(companyId, Pageable.unpaged()).getContent();
        return all.stream().filter(PriceList::isDefault).findFirst()
                .or(() -> all.stream().findFirst())
                .orElse(null);
    }

    private PriceList findPriceList(Long companyId, String code) {
        String c = code.trim();
        return priceLists.findByCompanyIdAndCode(companyId, c)
                .or(() -> priceLists.findByCompanyIdAndCode(companyId, c.toUpperCase()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_PRICE_LIST + "' '" + code + "' was not found."));
    }

    private String resolveUnitUid(Long companyId, String code) {
        String c = code.trim();
        return units.findByCompanyIdAndCode(companyId, c)
                .or(() -> units.findByCompanyIdAndCode(companyId, c.toUpperCase()))
                .map(u -> u.getUid())
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_UNIT + "' '" + code + "' is not a known unit-of-measure code."));
    }
}
