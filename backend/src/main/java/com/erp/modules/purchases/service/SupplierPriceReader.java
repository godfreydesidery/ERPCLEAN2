package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.entity.SupplierQuoteLine;
import com.erp.modules.purchases.repository.SupplierQuoteLineRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin read-only component for last-quoted price lookup (BR-PROC-09, ADR-0027 D-4).
 *
 * <p>Returns the most recent {@code unit_price_amount} from {@code supplier_quote_lines} for
 * a given (company, supplier, product) triple, ordered by the parent quote's {@code created_at}.
 * Called by purchase-order and requisition service impls to pre-fill unit cost suggestions.
 */
@Component
public class SupplierPriceReader {

    private final SupplierQuoteLineRepository quoteLines;

    public SupplierPriceReader(SupplierQuoteLineRepository quoteLines) {
        this.quoteLines = quoteLines;
    }

    /**
     * Returns the last quoted unit cost for a (company, supplier, product) combination,
     * or {@link Optional#empty()} if no quote history exists.
     */
    @Transactional(readOnly = true)
    public Optional<BigDecimal> lastQuotedUnitCost(Long companyId, Long supplierId, Long productId) {
        return quoteLines.findLastQuotedUnitCost(companyId, supplierId, productId);
    }

    /**
     * Most recent quote LINE for a (company, supplier, product, unit) combination — the amount plus
     * the currency and quote date needed to show a buyer where a suggested cost came from
     * ({@code PurchaseCostSuggestionService} step 1).
     *
     * <p>Unit-matched, unlike {@link #lastQuotedUnitCost}: the amount-only read above stays as-is for
     * the plain last-quoted-price reference endpoint, which has no unit in hand.
     *
     * @return the line, or {@link Optional#empty()} when this supplier has never quoted that
     *         product in that unit
     */
    @Transactional(readOnly = true)
    public Optional<SupplierQuoteLine> lastQuotedLine(Long companyId, Long supplierId,
                                                       Long productId, Long unitId) {
        return quoteLines.findLastQuotedLine(companyId, supplierId, productId, unitId,
                        PageRequest.of(0, 1))
                .stream().findFirst();
    }
}
