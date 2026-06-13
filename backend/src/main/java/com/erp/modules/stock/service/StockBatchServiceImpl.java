package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockBatchDto;
import com.erp.modules.stock.domain.entity.StockBatch;
import com.erp.modules.stock.repository.StockBatchRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lot/batch tracking service (ADR-0028 D-7, BR-INVD-10).
 *
 * <p>FEFO consumption order: earliest expiry first (expiry_date ASC NULLS LAST, id ASC).
 * Overselling stance: lot qty may go negative if the primary stock movement was allowed to proceed.
 */
@Service
@Transactional
public class StockBatchServiceImpl implements StockBatchService {

    private static final Logger log = LoggerFactory.getLogger(StockBatchServiceImpl.class);

    private final StockBatchRepository batches;
    private final ScopeGuard           scopeGuard;

    public StockBatchServiceImpl(StockBatchRepository batches,
                                  ScopeGuard scopeGuard) {
        this.batches    = batches;
        this.scopeGuard = scopeGuard;
    }

    // -------------------------------------------------------------------------
    // receiveQty
    // -------------------------------------------------------------------------

    @Override
    public StockBatchDto receiveQty(Long companyId, Long branchId, Long locationId, Long productId,
                                     String lotNumber, LocalDate manufactureDate, LocalDate expiryDate,
                                     BigDecimal qty, Long actorId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Batch receipt qty must be > 0; got: " + qty);
        }

        StockBatch batch = batches.findByCompanyIdAndBranchIdAndLocationIdAndProductIdAndLotNumber(
                        companyId, branchId, locationId, productId, lotNumber)
                .orElse(null);

        if (batch == null) {
            batch = new StockBatch(companyId, branchId, locationId, productId,
                    lotNumber, manufactureDate, expiryDate, actorId);
            batches.save(batch);
        }

        batch.applyDelta(qty, actorId);
        batches.save(batch);

        log.debug("StockBatch receiveQty: lot={} product={} qty=+{} → newQtyOnHand={}",
                lotNumber, productId, qty, batch.getQtyOnHand());

        return toDto(batch);
    }

    // -------------------------------------------------------------------------
    // consumeFefo
    // -------------------------------------------------------------------------

    @Override
    public void consumeFefo(Long companyId, Long locationId, Long productId,
                             BigDecimal qty, Long actorId) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("FEFO consume qty must be > 0; got: " + qty);
        }

        List<StockBatch> fefoLots = batches.findForFefoConsume(companyId, locationId, productId);
        BigDecimal remaining = qty;

        for (StockBatch lot : fefoLots) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;

            BigDecimal deduct = remaining.min(lot.getQtyOnHand());
            lot.applyDelta(deduct.negate(), actorId);
            batches.save(lot);
            remaining = remaining.subtract(deduct);

            log.debug("StockBatch consumeFefo: lot={} product={} deducted={} remaining={}",
                    lot.getLotNumber(), productId, deduct, remaining);
        }

        if (remaining.compareTo(BigDecimal.ZERO) > 0) {
            // Overselling stance — log a warning; the primary stock movement already allowed it
            log.warn("StockBatch consumeFefo: qty {} remaining after exhausting all lots " +
                    "for product={} location={} company={} — overselling stance applies",
                    remaining, productId, locationId, companyId);
        }
    }

    // -------------------------------------------------------------------------
    // reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public StockBatchDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        StockBatch b = batches.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StockBatch", uid));
        scopeGuard.assertCanActIn(principal, b.getCompanyId());
        return toDto(b);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockBatchDto> listAtLocation(Long companyId, Long locationId, Long productId,
                                               Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        // Use the FEFO finder (already filters by companyId+locationId+productId+qty>0)
        // For "all" (including zero-qty), a simple JPA query is sufficient.
        // findForFefoConsume returns qty>0 rows only — good enough for on-hand batch listing.
        List<StockBatch> all = batches.findForFefoConsume(companyId, locationId, productId);
        // manual pagination (list is typically small per location-product)
        int total = all.size();
        int from  = (int) Math.min(pageable.getOffset(), total);
        int to    = (int) Math.min(pageable.getOffset() + pageable.getPageSize(), total);
        List<StockBatchDto> page = all.subList(from, to).stream().map(StockBatchServiceImpl::toDto).toList();
        return new PageImpl<>(page, pageable, total);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockBatchDto> listExpiring(Long companyId, LocalDate horizon, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return batches.findExpiring(companyId, horizon, pageable)
                .map(StockBatchServiceImpl::toDto);
    }

    // -------------------------------------------------------------------------
    // Mapping
    // -------------------------------------------------------------------------

    private static StockBatchDto toDto(StockBatch b) {
        boolean expired = b.getExpiryDate() != null &&
                b.getExpiryDate().isBefore(LocalDate.now());
        return new StockBatchDto(
                b.getId(), b.getUid(),
                b.getCompanyId(), b.getBranchId(), b.getLocationId(), b.getProductId(),
                b.getLotNumber(), b.getManufactureDate(), b.getExpiryDate(),
                b.getQtyOnHand(), expired);
    }
}
