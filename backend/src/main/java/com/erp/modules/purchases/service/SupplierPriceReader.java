package com.erp.modules.purchases.service;

import com.erp.modules.purchases.repository.SupplierQuoteLineRepository;
import java.math.BigDecimal;
import java.util.Optional;
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
}
