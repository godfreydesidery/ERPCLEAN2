package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ProductBranchDto;
import com.erp.modules.products.domain.dto.SetBranchMenuRequest;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBranch;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.service.ProductBranchGuard.BranchRef;
import com.erp.platform.bulk.BulkImportHandler;
import com.erp.platform.bulk.ColumnSpec;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportParsers;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk BRANCH MENU and BRANCH PRICES (ADR-0073) — one sheet, one row per item, and for every active
 * branch of the company a "Sold here" and a "Price here" column. Opening a branch, or re-pricing one,
 * becomes download → edit in Excel → check → upload instead of one item at a time.
 *
 * <p><b>Nothing changes unless a cell says so.</b> A blank cell leaves that branch's value as it is;
 * {@code CLEAR} in a price cell removes the branch price (the company price applies again). Each cell
 * is compared with the item's CURRENT state first and only a real difference is written, so
 * re-uploading an untouched download changes nothing and writes no audit noise.
 *
 * <p><b>Same rules as the single-item screen.</b> Every change goes through
 * {@link ProductService#setBranchMenu}: the "no rows = sold everywhere" rule and its first-row
 * materialisation (D-1), "a price alone does not start selling an item" (D-1), the company scope
 * check and the audit trail. Each row is its own transaction (framework), so tills trading at the
 * same time are never held up, and a bad row fails alone.
 */
@Component
@Transactional
public class BranchMenuImportHandler implements BulkImportHandler {

    static final String COL_PRODUCT = "Product Code";
    static final String COL_NAME = "Product Name";
    static final String COL_COMPANY_PRICE = "Company Price";
    static final String CLEAR = "CLEAR";

    private static final int EXPORT_MAX = 50_000;

    private final ProductService productService;
    private final ProductRepository products;
    private final ProductBranchRepository productBranches;
    private final PriceListRepository priceLists;
    private final ProductPriceRepository prices;
    private final ProductBranchGuard branchGuard;

    public BranchMenuImportHandler(ProductService productService,
                                   ProductRepository products,
                                   ProductBranchRepository productBranches,
                                   PriceListRepository priceLists,
                                   ProductPriceRepository prices,
                                   ProductBranchGuard branchGuard) {
        this.productService = productService;
        this.products = products;
        this.productBranches = productBranches;
        this.priceLists = priceLists;
        this.prices = prices;
        this.branchGuard = branchGuard;
    }

    @Override
    public String key() {
        return "branch-menu";
    }

    @Override
    public String displayName() {
        return "Branch menu & prices";
    }

    @Override
    public String permissionCode() {
        // The permission the single-item branch-menu endpoints already demand — no new code.
        return "PRODUCT.BRANCH.ASSIGN";
    }

    /** "Sold here: CAFE TULIA KIA [Tulia003]" — the name to read, the code to keep it unique. */
    static String soldHeader(BranchRef b) {
        return "Sold here: " + b.name() + " [" + b.code() + "]";
    }

    static String priceHeader(BranchRef b) {
        return "Price here: " + b.name() + " [" + b.code() + "]";
    }

    @Override
    public List<ColumnSpec> columns(Long companyId) {
        List<ColumnSpec> cols = new ArrayList<>();
        cols.add(ColumnSpec.of(COL_PRODUCT, true, "Existing product code."));
        cols.add(ColumnSpec.reference(COL_NAME, "The item's name — to identify the row."));
        cols.add(ColumnSpec.numberReference(COL_COMPANY_PRICE,
                "The company price (default price list, per base unit): what a branch charges when "
              + "it has no price of its own."));
        for (BranchRef b : branchGuard.activeBranchesOf(companyId)) {
            cols.add(ColumnSpec.choice(soldHeader(b), false,
                    "Yes = sold at " + b.name() + "; No = not sold there. Blank = leave as it is.",
                    List.of("Yes", "No")));
            cols.add(ColumnSpec.number(priceHeader(b), false,
                    b.name() + "'s own price per base unit, with the same VAT treatment as the "
                  + "company price. Blank = leave as it is. Type CLEAR to remove it, so the company "
                  + "price applies there again."));
        }
        return cols;
    }

    /** What one row asks of one branch: null fields = leave as it is. */
    private record Ask(BranchRef branch, Boolean soldHere, BigDecimal price, boolean clearPrice) {
        boolean asksAnything() {
            return soldHere != null || price != null || clearPrice;
        }
    }

    @Override
    public RowOutcome process(Long companyId, ImportRow row, ImportMode mode, ImportContext ctx) {
        String code = ImportParsers.requireText(row, COL_PRODUCT).trim();
        // One row per item: a second row for the same item would silently override the first.
        if (!ctx.claimSet("branch-menu-product").add(code.toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "'" + code + "' appears more than once in this file. Keep one row per item.");
        }

        // Read and check EVERY cell before writing anything, so a bad cell rejects the whole row
        // rather than leaving it half applied (the row's transaction would roll back anyway).
        List<Ask> asks = new ArrayList<>();
        for (BranchRef b : branchGuard.activeBranchesOf(companyId)) {
            Ask ask = new Ask(b, ImportParsers.parseBool(row, soldHeader(b), null),
                    priceOf(row, priceHeader(b)), CLEAR.equalsIgnoreCase(row.get(priceHeader(b))));
            if (ask.asksAnything()) {
                asks.add(ask);
            }
        }
        if (asks.isEmpty()) {
            return RowOutcome.skip(row.rowNumber(), code,
                    "No branch cell filled in — left unchanged.");
        }

        Product product = findProduct(companyId, code);
        List<ProductBranchDto> state = productService.listBranches(product.getUid());
        List<String> changes = new ArrayList<>();
        for (Ask ask : asks) {
            Optional<ProductBranchDto> here = rowFor(state, ask.branch().id());
            // ADR-0073 D-1: no rows at all = sold everywhere; with rows, only where active.
            boolean soldNow = state.isEmpty() || here.map(ProductBranchDto::active).orElse(false);
            BigDecimal priceNow = here.map(ProductBranchDto::branchPrice).orElse(null);

            Boolean soldChange = ask.soldHere() != null && ask.soldHere() != soldNow
                    ? ask.soldHere() : null;
            BigDecimal priceChange = ask.price() != null
                    && (priceNow == null || ask.price().compareTo(priceNow) != 0)
                    ? ask.price() : null;
            boolean clearChange = ask.clearPrice() && priceNow != null;
            if (soldChange == null && priceChange == null && !clearChange) {
                continue;   // already as asked — write nothing, record nothing
            }

            state = productService.setBranchMenu(product.getUid(), new SetBranchMenuRequest(
                    ask.branch().uid(), soldChange, priceChange, clearChange ? Boolean.TRUE : null));
            changes.add(describe(ask.branch(), soldChange, priceNow, priceChange, clearChange,
                    rowFor(state, ask.branch().id()).map(ProductBranchDto::active).orElse(false)));
        }

        if (changes.isEmpty()) {
            return RowOutcome.skip(row.rowNumber(), product.getCode(),
                    "Already as in the sheet — nothing to change.");
        }
        return RowOutcome.update(row.rowNumber(),
                product.getCode() + " (" + String.join("; ", changes) + ")");
    }

    /** A price cell: blank or CLEAR = no new price; otherwise a number of zero or more. */
    private static BigDecimal priceOf(ImportRow row, String header) {
        String v = row.get(header);
        if (v.isEmpty() || CLEAR.equalsIgnoreCase(v)) {
            return null;
        }
        BigDecimal price;
        try {
            price = ImportParsers.parseDecimal(row, header);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "'" + header + "' must be a price, blank, or CLEAR (got '" + v + "').");
        }
        if (price != null && price.signum() < 0) {
            throw new IllegalArgumentException("'" + header + "' cannot be negative.");
        }
        return price;
    }

    private static String describe(BranchRef b, Boolean soldChange, BigDecimal priceBefore,
                                   BigDecimal priceChange, boolean clearChange, boolean soldAfter) {
        List<String> parts = new ArrayList<>(2);
        if (soldChange != null) {
            parts.add(soldChange ? "now sold" : "no longer sold");
        }
        if (priceChange != null) {
            parts.add(priceBefore == null
                    ? "price " + plain(priceChange)
                    : "price " + plain(priceBefore) + " → " + plain(priceChange));
            if (!soldAfter) {
                // D-1: a price alone never starts selling an item — say so, or it reads as a bug.
                parts.add("not sold there");
            }
        }
        if (clearChange) {
            parts.add("branch price removed (company price applies)");
        }
        return b.name() + ": " + String.join(", ", parts);
    }

    private Product findProduct(Long companyId, String code) {
        return products.findByCompanyIdAndCode(companyId, code)
                .or(() -> products.findByCompanyIdAndCode(companyId, code.toUpperCase(Locale.ROOT)))
                .orElseThrow(() -> new IllegalArgumentException(
                        "'" + COL_PRODUCT + "' '" + code + "' was not found."));
    }

    private static Optional<ProductBranchDto> rowFor(List<ProductBranchDto> state, Long branchId) {
        return state.stream().filter(r -> branchId.equals(r.branchId())).findFirst();
    }

    // -------------------------------------------------------------------------
    // Export: the current menu and prices, so download → edit → upload works
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<LinkedHashMap<String, String>> exportRows(Long companyId, Map<String, String> params) {
        List<BranchRef> branches = branchGuard.activeBranchesOf(companyId);

        Map<Long, List<ProductBranch>> rowsByProduct = new HashMap<>();
        for (ProductBranch pb : productBranches.findByProductCompanyId(companyId)) {
            rowsByProduct.computeIfAbsent(pb.getProduct().getId(), k -> new ArrayList<>()).add(pb);
        }
        Map<Long, BigDecimal> companyPrice = companyPrices(companyId);

        return products.findByCompanyId(companyId, Pageable.unpaged()).getContent().stream()
                .filter(p -> p.getStatus() == MasterStatus.ACTIVE)
                .sorted(Comparator.comparing(Product::getName, String.CASE_INSENSITIVE_ORDER))
                .limit(EXPORT_MAX)
                .map(p -> exportRow(p, branches, rowsByProduct.getOrDefault(p.getId(), List.of()),
                        companyPrice.get(p.getId())))
                .toList();
    }

    private static LinkedHashMap<String, String> exportRow(Product p, List<BranchRef> branches,
                                                           List<ProductBranch> rows,
                                                           BigDecimal companyPrice) {
        LinkedHashMap<String, String> r = new LinkedHashMap<>();
        r.put(COL_PRODUCT, p.getCode());
        r.put(COL_NAME, p.getName());
        r.put(COL_COMPANY_PRICE, companyPrice == null ? "" : plain(companyPrice));
        for (BranchRef b : branches) {
            Optional<ProductBranch> here = rows.stream()
                    .filter(pb -> b.id().equals(pb.getBranchId())).findFirst();
            boolean sold = rows.isEmpty() || here.map(ProductBranch::isActive).orElse(false);
            r.put(soldHeader(b), sold ? "Yes" : "No");
            r.put(priceHeader(b), here.map(ProductBranch::getBranchPrice)
                    .map(BranchMenuImportHandler::plain).orElse(""));
        }
        return r;
    }

    /** Base-unit prices on the company's default list (else its first list), keyed by product id. */
    private Map<Long, BigDecimal> companyPrices(Long companyId) {
        List<PriceList> lists = priceLists.findByCompanyId(companyId, Pageable.unpaged()).getContent();
        Optional<PriceList> list = lists.stream().filter(PriceList::isDefault).findFirst()
                .or(() -> lists.stream().findFirst());
        Map<Long, BigDecimal> out = new HashMap<>();
        list.ifPresent(pl -> {
            for (ProductPrice pp : prices.findByCompanyIdAndPriceListId(companyId, pl.getId())) {
                if (pp.getUnit() == null && pp.getPrice() != null) {
                    out.put(pp.getProduct().getId(), pp.getPrice().getAmount());
                }
            }
        });
        return out;
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }
}
