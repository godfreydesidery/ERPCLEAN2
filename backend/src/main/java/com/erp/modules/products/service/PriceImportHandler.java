package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.SetProductPriceRequest;
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
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk price upload / price-change (a mass "price change" operation). Each row sets one product's
 * price on a named price list, upserting through {@link ProductService#setPrice} — same validation,
 * scope and audit as the single-price screen. Address products and price lists by their human codes;
 * an optional Unit column targets a pack price (blank = the base-unit price).
 *
 * <p><b>Download → edit → re-upload:</b> {@link #exportRows} emits one row per product for a chosen
 * price list (the current price, or blank if none). On re-upload a <b>blank Amount is SKIPPED</b>
 * (the price is left unchanged) — so you can download every product, edit only the ones you want,
 * and upload the whole sheet.
 *
 * <p>For an across-the-board change (e.g. "raise RETAIL by 5%") use the rule-based mass price change
 * instead — this handler is for arbitrary per-product edits.
 */
@Component
@Transactional
public class PriceImportHandler implements BulkImportHandler {

    private static final int EXPORT_MAX = 2000;

    private static final String COL_PRODUCT = "Product Code";
    private static final String COL_NAME = "Product Name";
    private static final String COL_PRICE_LIST = "Price List";
    private static final String COL_UNIT = "Unit";
    private static final String COL_AMOUNT = "Amount";
    private static final String COL_CURRENCY = "Currency";
    private static final String COL_COST = "Cost";

    private final ProductService productService;
    private final ProductRepository products;
    private final PriceListRepository priceLists;
    private final ProductPriceRepository prices;
    private final UnitOfMeasureRepository units;

    public PriceImportHandler(ProductService productService,
                              ProductRepository products,
                              PriceListRepository priceLists,
                              ProductPriceRepository prices,
                              UnitOfMeasureRepository units) {
        this.productService = productService;
        this.products = products;
        this.priceLists = priceLists;
        this.prices = prices;
        this.units = units;
    }

    @Override
    public String key() {
        return "prices";
    }

    @Override
    public String displayName() {
        return "Product prices";
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
                ? ColumnSpec.of(COL_PRICE_LIST, true, "Existing price-list code, e.g. RETAIL.")
                : ColumnSpec.choice(COL_PRICE_LIST, true, "Existing price-list code.", priceListCodes);
        return List.of(
                ColumnSpec.of(COL_PRODUCT, true, "Existing product code."),
                ColumnSpec.reference(COL_NAME, "The product's name — to identify the row you're pricing."),
                priceList,
                ColumnSpec.of(COL_UNIT, false,
                        "Blank = base-unit price. A unit code sets that pack's price (the pack must "
                      + "already be configured on the product)."),
                ColumnSpec.of(COL_AMOUNT, false,
                        "The price on this list. Leave blank to LEAVE THE PRICE UNCHANGED (the row is skipped)."),
                ColumnSpec.of(COL_CURRENCY, false, "3-letter code, e.g. TZS. Required when Amount is set."),
                ColumnSpec.reference(COL_COST, "The product's current cost — for margin reference."));
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        // Blank price = intentional no-op, so a full export can be edited row-by-row and re-uploaded.
        if (!row.has(COL_AMOUNT)) {
            return RowOutcome.skip(row.rowNumber(), row.get(COL_PRODUCT), "No price entered — left unchanged.");
        }

        String productCode = ImportParsers.requireText(row, COL_PRODUCT);
        Product product = products.findByCompanyIdAndCode(companyId, productCode.trim().toUpperCase())
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_PRODUCT + "' '" + productCode + "' was not found."));

        String priceListCode = ImportParsers.requireText(row, COL_PRICE_LIST);
        PriceList priceList = findPriceList(companyId, priceListCode);

        BigDecimal amount = ImportParsers.parseDecimal(row, COL_AMOUNT);
        String currency = ImportParsers.requireText(row, COL_CURRENCY).toUpperCase();
        String unitUid = row.has(COL_UNIT) ? resolveUnitUid(companyId, row.get(COL_UNIT)) : null;

        productService.setPrice(product.getUid(), new SetProductPriceRequest(
                priceList.getUid(), new MoneyDto(amount.toPlainString(), currency), unitUid));

        return RowOutcome.update(row.rowNumber(),
                productCode.trim().toUpperCase() + " @ " + priceList.getCode());
    }

    @Override
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        PriceList priceList = resolveExportPriceList(companyId, params);
        if (priceList == null) {
            return List.of();
        }
        String listCurrency = priceList.getCurrency() != null ? priceList.getCurrency().value() : "";

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
                .map(p -> exportRow(p, priceList, baseByProduct.get(p.getId()), listCurrency))
                .toList();
    }

    /** One export row: the editable price fields plus the Name/Cost reference context. */
    private static LinkedHashMap<String, String> exportRow(Product p, PriceList priceList,
                                                           ProductPrice pp, String listCurrency) {
        boolean priced = pp != null && pp.getPrice() != null;
        String amount = priced ? pp.getPrice().getAmount().toPlainString() : "";
        String currency = priced ? pp.getPrice().getCurrency().value() : listCurrency;
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put(COL_PRODUCT, p.getCode());
        r.put(COL_NAME, p.getName());
        r.put(COL_PRICE_LIST, priceList.getCode());
        r.put(COL_UNIT, "");
        r.put(COL_AMOUNT, amount);
        r.put(COL_CURRENCY, currency);
        r.put(COL_COST, costOf(p));
        return r;
    }

    /** The product's current cost amount as plain text, or blank when unset. */
    private static String costOf(Product p) {
        return p.getCost() != null && p.getCost().getAmount() != null
                ? p.getCost().getAmount().toPlainString() : "";
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
