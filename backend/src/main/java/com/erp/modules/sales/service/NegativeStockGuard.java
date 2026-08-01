package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.modules.stock.domain.dto.StockAvailabilityDto;
import com.erp.modules.stock.service.RecipeExplosionResolver;
import com.erp.modules.stock.service.StockReservationService;
import com.erp.platform.common.api.ConflictException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the per-company "block negative stock on sale" Sales Setting
 * ({@code sales_settings.allow_negative_stock}, V87, owner decision 2026-07-05) at every
 * synchronous sale-issue path: DIRECT/POS invoice finalise
 * ({@link SalesInvoiceServiceImpl#finalise}, which covers POS too — {@link PosSaleServiceImpl}
 * delegates to the same {@code finalise}) and SO delivery create ({@link DeliveryServiceImpl#create}).
 *
 * <p><b>This is the enforcement point.</b> The actual stock deduction for both paths happens
 * later, out-of-band, in the async stock-module consumers ({@code SaleIssueStockHandler},
 * {@code DeliveryIssueStockHandler}) dispatched from the transactional outbox. Those consumers do
 * not — and must not — re-check the setting: they have no visibility into Sales Settings (a
 * sales-module concept) and by the time they run, the synchronous decision made here has already
 * committed. Blocking here, before the triggering write commits, is what keeps a rejected sale from
 * ever reaching the outbox.
 *
 * <p><b>Scope — lines that are issued as themselves.</b> Mirrors the branching in
 * {@code SaleIssueStockHandler.processLine}, using the very same predicate
 * ({@link RecipeExplosionResolver#shouldExplodeAtIssue}) so what is checked here and what is
 * deducted there can never drift apart:
 * <ul>
 *   <li>non-stockable products (services) never move stock — skipped, exactly as the handler skips
 *       them (BR-STOCK-02/04);</li>
 *   <li>products that EXPLODE at issue time (ADR-0058: a point-of-sale kit with
 *       {@code product_components}, or a non-stockable phantom assembled via a BOM) carry no on-hand
 *       row of their own — checking the parent's own id would false-positive block every kit sale.
 *       Skipped here (out of scope for this pass); a future ADR can extend the guard to check each
 *       exploded component's availability if that becomes a real gap;</li>
 *   <li>a STOCKABLE finished good whose only recipe is a manufacturing BOM is make-to-stock: the
 *       handler issues it as ITSELF, so it IS checked here. It used to be skipped, because the
 *       guard tested a broader "is this composed at all?" predicate (since removed) rather than
 *       the handler's "does this explode at issue?" — drift that let such a product go straight
 *       negative with the setting on. Both sides now use the same predicate.</li>
 * </ul>
 *
 * <p>Availability = {@code stock_on_hand.quantity − reserved_qty} at the branch default location
 * (ADR-0021 D-5 formula), read via {@link StockReservationService#getAvailability} — a cross-module
 * DTO read, not an entity import (module-boundary rule).
 */
@Component
public class NegativeStockGuard {

    private final SalesSettingsRepository settings;
    private final StockReservationService stock;
    private final RecipeExplosionResolver explosion;

    public NegativeStockGuard(SalesSettingsRepository settings,
                              StockReservationService stock,
                              RecipeExplosionResolver explosion) {
        this.settings  = settings;
        this.stock     = stock;
        this.explosion = explosion;
    }

    /**
     * Throws a friendly {@link ConflictException} (no ids/uids/internal codes — error-message
     * hygiene rule) when issuing {@code qtyRequestedBase} of this line's product would take
     * (company, branch, product) on-hand negative and the company has not opted into backorder.
     *
     * @param companyId         tenant
     * @param branchId          branch the sale issues stock from
     * @param productId         the line product's internal id
     * @param productUid        the line product's uid — used only to test whether it explodes at issue
     * @param productStockable  the line product's own {@code stockable} flag
     * @param productName       for the user-facing message only
     * @param qtyRequestedBase  requested quantity, base units
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAvailable(Long companyId, Long branchId,
                                Long productId, String productUid, boolean productStockable,
                                String productName, BigDecimal qtyRequestedBase) {
        if (!productStockable || explosion.shouldExplodeAtIssue(productUid, productStockable)) {
            return; // no direct movement against this product's own SKU — nothing to guard here
        }
        if (qtyRequestedBase == null || qtyRequestedBase.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        boolean allowNegative = settings.findByCompanyId(companyId)
                .map(SalesSettings::isAllowNegativeStock)
                // No Sales Settings row → BLOCK (fail-safe). A row is now provisioned by
                // SalesSettingsSeeder when the company is created (and healed on re-provision), so
                // "un-configured" should not occur; if it somehow does, the safe reading of a missing
                // row is the same as the value every other layer reports for it — the entity/DB
                // default and the web toggle both say block. This deliberately REVERSES the earlier
                // "allow until configured" fallback (owner decision 2026-07-05): it disagreed with
                // what the Sales Settings screen showed for the very same missing row, so a company
                // that had never opened the screen oversold while being told it was protected.
                .orElse(false);
        if (allowNegative) {
            return;
        }

        StockAvailabilityDto avail = stock.getAvailability(companyId, branchId, productId);
        if (qtyRequestedBase.compareTo(avail.availableQty()) > 0) {
            // Busy-day-sim bugfix: a non-positive on-hand (common once a company has been
            // overselling from before this guard existed) used to print a raw 6dp negative like
            // "-2240.000000 available" — phrase it as plain "out of stock" instead. A positive
            // available figure is trimmed to its sensible precision (no trailing zeros).
            String availableText = avail.availableQty().compareTo(BigDecimal.ZERO) <= 0
                    ? "out of stock"
                    : formatQty(avail.availableQty()) + " available";
            throw new ConflictException(
                    "Not enough stock of " + productName + " to complete this sale — "
                            + availableText + ", "
                            + formatQty(qtyRequestedBase) + " requested. "
                            + "Ask a supervisor to enable backorder if this should be allowed.");
        }
    }

    /** Trims a quantity to its sensible precision — whole units print without a decimal point. */
    private static String formatQty(BigDecimal qty) {
        return qty.stripTrailingZeros().toPlainString();
    }
}
