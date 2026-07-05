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
 * <p><b>Scope — simple stockable lines only.</b> Mirrors {@code SaleIssueStockHandler.processLine}'s
 * own branching:
 * <ul>
 *   <li>non-stockable products (services) never move stock — skipped, exactly as the handler skips
 *       them (BR-STOCK-02/04);</li>
 *   <li>composed/BOM products explode to their components at issue time and typically carry no
 *       on-hand row of their own — checking the composed product's own id would false-positive
 *       block every kit/bundle sale. Skipped here (out of scope for this pass); a future ADR can
 *       extend the guard to check each exploded component's availability if that becomes a real gap.</li>
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
     * @param productUid        the line product's uid — used only to test compose-ness
     * @param productStockable  the line product's own {@code stockable} flag
     * @param productName       for the user-facing message only
     * @param qtyRequestedBase  requested quantity, base units
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertAvailable(Long companyId, Long branchId,
                                Long productId, String productUid, boolean productStockable,
                                String productName, BigDecimal qtyRequestedBase) {
        if (!productStockable || explosion.isComposed(productUid)) {
            return; // no direct movement against this product's own SKU — nothing to guard here
        }
        if (qtyRequestedBase == null || qtyRequestedBase.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        boolean allowNegative = settings.findByCompanyId(companyId)
                .map(SalesSettings::isAllowNegativeStock)
                .orElse(false); // no settings row yet for this company → block (entity default)
        if (allowNegative) {
            return;
        }

        StockAvailabilityDto avail = stock.getAvailability(companyId, branchId, productId);
        if (qtyRequestedBase.compareTo(avail.availableQty()) > 0) {
            throw new ConflictException(
                    "Not enough stock of " + productName + " to complete this sale — "
                            + avail.availableQty().toPlainString() + " available, "
                            + qtyRequestedBase.toPlainString() + " requested. "
                            + "To allow this, enable backorder in Sales Settings.");
        }
    }
}
