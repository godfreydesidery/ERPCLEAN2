package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch wrapper over {@link PriceResolutionService#resolveUnitListPriceQuote} — see
 * {@link UnitPriceLookupService} for why it exists. Deliberately contains no pricing rules.
 */
@Service
@Transactional(readOnly = true)
public class UnitPriceLookupServiceImpl implements UnitPriceLookupService {

    private static final Logger log = LoggerFactory.getLogger(UnitPriceLookupServiceImpl.class);

    /**
     * Defence in depth behind the request DTO's {@code @Size} — an unbounded IN-list is a cheap way
     * to make one HTTP call scan the whole catalogue.
     */
    private static final int MAX_BATCH = 200;

    private final ProductRepository products;
    private final UnitOfMeasureRepository units;
    private final PriceResolutionService priceResolution;

    public UnitPriceLookupServiceImpl(ProductRepository products,
                                      UnitOfMeasureRepository units,
                                      PriceResolutionService priceResolution) {
        this.products = products;
        this.units = units;
        this.priceResolution = priceResolution;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResolvedUnitPriceDto> resolve(ResolveUnitPricesRequest request) {
        Long companyId = activeCompanyId();

        // De-duplicate but keep request order — a POS page renders rows in the order it asked.
        Set<String> requestedUids = new LinkedHashSet<>();
        if (request.productUids() != null) {
            for (String uid : request.productUids()) {
                if (uid != null && !uid.isBlank()) {
                    requestedUids.add(uid.trim());
                }
            }
        }
        if (requestedUids.isEmpty()) {
            return List.of();
        }
        if (requestedUids.size() > MAX_BATCH) {
            throw new IllegalArgumentException(
                    "Ask for at most 200 products in one price request.");
        }

        // Optional unit: resolved ONCE for the batch, scoped to the active company. A unit uid that
        // does not exist is a client bug, not a per-row condition — fail the call.
        Long requestedUnitId = null;
        String requestedUnitUid = trimToNull(request.unitUid());
        if (requestedUnitUid != null) {
            UnitOfMeasure unit = units.findByCompanyIdAndUid(companyId, requestedUnitUid)
                    .orElseThrow(() -> new NotFoundException("Unit of measure not found."));
            requestedUnitId = unit.getId();
        }

        Map<String, Product> byUid = new LinkedHashMap<>();
        for (Product product : products.findByCompanyIdAndUidIn(companyId, requestedUids)) {
            byUid.put(product.getUid(), product);
        }

        List<ResolvedUnitPriceDto> rows = new ArrayList<>(byUid.size());
        for (String uid : requestedUids) {
            Product product = byUid.get(uid);
            if (product == null) {
                // Unknown uid — or one belonging to another company, which the scoped query already
                // filtered out. Ignored either way: one stale uid must not blank a whole POS page,
                // and omitting both makes "does not exist" indistinguishable from "not yours".
                continue;
            }
            rows.add(resolveOne(product, requestedUnitId, requestedUnitUid));
        }
        return rows;
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Prices one product, converting the resolver's per-product rejections into a row status.
     * Company scope comes from the LOADED product, never from anything the caller supplied.
     *
     * <p>Uses the resolver's NON-throwing {@code findUnitListPriceQuote}. It used to call the
     * throwing {@code resolveUnitListPriceQuote} inside a try/catch, which looked tolerant and was
     * not: {@code PriceResolutionServiceImpl} is itself {@code @Transactional} and joins THIS
     * read-only transaction, so its {@code IllegalArgumentException} /
     * {@code IllegalStateException} marked the shared transaction rollback-only on the way out of
     * the proxy. Catching them here produced a perfectly good {@code List} and then an
     * {@code UnexpectedRollbackException} at commit — HTTP 500 for the whole page, and the
     * documented {@code NO_PRICE} / {@code UNIT_NOT_APPLICABLE} statuses were unreachable in
     * production (110 of 917 live products have no price row, so a 50-row till page hits it often).
     * Asking for the outcome as a value never touches the transaction.
     */
    private ResolvedUnitPriceDto resolveOne(Product product, Long requestedUnitId,
                                            String requestedUnitUid) {
        UnitOfMeasure baseUnit = product.getBaseUnit();
        Long unitId = requestedUnitId != null ? requestedUnitId : baseUnit.getId();
        String unitUid = requestedUnitUid != null ? requestedUnitUid : baseUnit.getUid();

        UnitPriceQuoteResult result = priceResolution.findUnitListPriceQuote(
                product.getCompanyId(), product.getId(), unitId);
        Optional<UnitPriceQuoteDto> quote = result.quote();
        if (quote.isEmpty()) {
            // NO_PRICE (no usable price row) or UNIT_NOT_APPLICABLE (the requested unit is neither
            // the base nor a configured pack for THIS product — expected when one unit is applied
            // across a mixed batch). Either way the row carries the reason; the batch survives.
            log.debug("Product {} not priced in unit {}: {}",
                    product.getId(), unitId, result.status());
            return ResolvedUnitPriceDto.unpriced(product.getUid(), unitUid, result.status());
        }
        UnitPriceQuoteDto priced = quote.get();
        return new ResolvedUnitPriceDto(product.getUid(), unitUid, priced.amount(),
                priced.currency(), priced.vatInclusive(), UnitPriceStatus.RESOLVED);
    }

    /** The caller's active company, taken from the request context — never from the request body. */
    private Long activeCompanyId() {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null || principal.companyId() == null) {
            throw new ForbiddenException("No active company for this session.");
        }
        return principal.companyId();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
