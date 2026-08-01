package com.erp.modules.purchases.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.modules.purchases.domain.dto.PurchaseCostSuggestionDto;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.modules.purchases.domain.enums.PurchaseCostSource;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.common.money.Money;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only unit-cost suggestion for purchase-order lines (SAM client feedback 2026-08).
 *
 * <p>Security: {@code scopeGuard.assertCanActIn} on the loaded PO, then supplier/product/unit are
 * all resolved scoped to THAT PO's company — never to a caller-supplied company (F15).
 *
 * <p>Every source is matched on the requested unit: prices are per unit, so a carton price must
 * never be offered for a line ordered per piece. That is also why the product-master cost only
 * applies to the base unit — the unit it is expressed in.
 */
@Service
@Transactional(readOnly = true)
public class PurchaseCostSuggestionServiceImpl implements PurchaseCostSuggestionService {

    private final PurchaseOrderRepository     orders;
    private final PurchaseOrderLineRepository lines;
    private final ProductRepository           products;
    private final UnitOfMeasureRepository     units;
    private final SupplierPriceReader         supplierPrices;
    private final ScopeGuard                  scopeGuard;

    public PurchaseCostSuggestionServiceImpl(PurchaseOrderRepository orders,
                                             PurchaseOrderLineRepository lines,
                                             ProductRepository products,
                                             UnitOfMeasureRepository units,
                                             SupplierPriceReader supplierPrices,
                                             ScopeGuard scopeGuard) {
        this.orders         = orders;
        this.lines          = lines;
        this.products       = products;
        this.units          = units;
        this.supplierPrices = supplierPrices;
        this.scopeGuard     = scopeGuard;
    }

    @Override
    public Optional<PurchaseCostSuggestionDto> suggestUnitCost(String purchaseOrderUid,
                                                               String productUid,
                                                               String unitUid) {
        PurchaseOrder po = Lookups.orNotFound(orders.findByUid(purchaseOrderUid),
                "PurchaseOrder", purchaseOrderUid);
        scopeGuard.assertCanActIn(RequestContext.get(), po.getCompanyId());

        Long companyId = po.getCompanyId();
        Product product = products.findByCompanyIdAndUid(companyId, productUid)
                .orElseThrow(() -> new NotFoundException("Product not found."));
        UnitOfMeasure unit = units.findByCompanyIdAndUid(companyId, unitUid)
                .orElseThrow(() -> new NotFoundException("Unit of measure not found."));

        // First hit wins — see the interface javadoc for why avg cost on hand is not in the chain.
        Optional<PurchaseCostSuggestionDto> fromQuote =
                fromLastQuote(companyId, po.getSupplierId(), product, unit);
        if (fromQuote.isPresent()) {
            return fromQuote;
        }
        Optional<PurchaseCostSuggestionDto> fromPurchase =
                fromLastPurchase(companyId, po.getSupplierId(), product, unit);
        if (fromPurchase.isPresent()) {
            return fromPurchase;
        }
        return fromProductCost(product, unit);
    }

    // -------------------------------------------------------------------------
    // Sources, in fallback order
    // -------------------------------------------------------------------------

    /** (1) Last price this supplier quoted for this product/unit (BR-PROC-09). */
    private Optional<PurchaseCostSuggestionDto> fromLastQuote(Long companyId, Long supplierId,
                                                              Product product, UnitOfMeasure unit) {
        return supplierPrices.lastQuotedLine(companyId, supplierId, product.getId(), unit.getId())
                .flatMap(line -> suggestion(
                        line.getUnitPriceAmount(),
                        CurrencyCode.value(line.getCurrency()),
                        PurchaseCostSource.LAST_QUOTE,
                        toDate(line.getCreatedAt())));
    }

    /** (2) Last price actually ordered from this supplier for this product/unit. */
    private Optional<PurchaseCostSuggestionDto> fromLastPurchase(Long companyId, Long supplierId,
                                                                 Product product, UnitOfMeasure unit) {
        return lines.findLastPurchasedLine(companyId, supplierId, product.getId(), unit.getId(),
                        PageRequest.of(0, 1))
                .stream().findFirst()
                .flatMap(line -> suggestion(
                        line.getUnitCostAmount(),
                        CurrencyCode.value(line.getCurrency()),
                        PurchaseCostSource.LAST_PURCHASE,
                        toDate(orderedAt(line))));
    }

    /**
     * (3) The product master's cost (buying) price. Offered only for the product's base unit — that
     * is the unit the cost is expressed in, and converting it through a bulk-pack factor would be
     * inventing a price the buyer never agreed. Carries no date: a master field, not an event.
     */
    private Optional<PurchaseCostSuggestionDto> fromProductCost(Product product, UnitOfMeasure unit) {
        Money cost = product.getCost();
        if (cost == null || !unit.getId().equals(product.getBaseUnit().getId())) {
            return Optional.empty();
        }
        return suggestion(cost.getAmount(), CurrencyCode.value(cost.getCurrency()),
                PurchaseCostSource.PRODUCT_COST, null);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a suggestion, or nothing when the source has no usable price. A missing currency or a
     * non-positive amount is NOT a suggestion: zero would read as "this really costs nothing" and
     * quietly place a free-of-charge order, so the field is left blank instead.
     */
    private Optional<PurchaseCostSuggestionDto> suggestion(BigDecimal amount, String currency,
                                                           PurchaseCostSource source, LocalDate asOf) {
        if (amount == null || currency == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Optional.empty();
        }
        return Optional.of(new PurchaseCostSuggestionDto(amount, currency, source, asOf));
    }

    /** Placement timestamp of the line's order; non-null for the placed statuses the query allows. */
    private Instant orderedAt(PurchaseOrderLine line) {
        return line.getPurchaseOrder().getOrderedAt();
    }

    private LocalDate toDate(Instant at) {
        return at != null ? at.atZone(ZoneOffset.UTC).toLocalDate() : null;
    }
}
