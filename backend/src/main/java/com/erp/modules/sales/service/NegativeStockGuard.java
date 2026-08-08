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
 * <p>Availability = {@code stock_on_hand.quantity − reserved_qty} across the branch (ADR-0021 D-5
 * formula), obtained via {@link StockReservationService#reserve} — a cross-module DTO call, not an
 * entity import (module-boundary rule).
 *
 * <p><b>Check and claim are one step.</b> {@code reserve} locks the branch reservation row, reads
 * availability under that lock and adds the requested quantity to {@code reserved_qty}, all inside
 * this transaction. Merely reading availability was not enough: the deduction itself is asynchronous
 * (the outbox poller runs {@code SaleIssueStockHandler} about a second after finalise commits), so
 * concurrent sales all read the same pre-sale quantity and all passed — with 198 on hand and
 * blocking switched on, eight back-to-back sales of 30 units were every one of them accepted and
 * on-hand finished at −42. The claim is released by the stock handler in the same transaction that
 * posts the movement, so quantity falls and the claim lifts together.
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

        // Claim the quantity before deciding, in this transaction and behind the on-hand row lock
        // (see StockReservationService#reserve). Reading availability without claiming it made the
        // block trivially defeatable by ordinary speed: the deduction happens asynchronously about a
        // second later, so every sale started inside that window read the same pre-sale quantity and
        // every one of them passed — 198 on hand, blocking on, eight tills selling 30 each, eight
        // acceptances, −42 on hand and not one refusal. The claim is released by the stock handler in
        // the same transaction that finally posts the movement, so the two are never visible apart.
        //
        // The reservation is taken even when the company allows backorder, so that the release side
        // never has to know which way the setting stood when the sale was made — the handler releases
        // unconditionally, and a setting flipped between finalise and dispatch cannot strand a claim.
        StockAvailabilityDto avail = stock.reserve(
                companyId, branchId, productId, qtyRequestedBase, null /* actorId — system claim */);

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

        // Throwing rolls the caller's whole transaction back, and the reservation taken above goes
        // with it — nothing to unwind by hand, and a rejected sale leaves no claim behind.
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
