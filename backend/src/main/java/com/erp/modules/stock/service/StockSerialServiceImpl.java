package com.erp.modules.stock.service;

import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.stock.domain.dto.StockSerialDto;
import com.erp.modules.stock.domain.entity.StockSerial;
import com.erp.modules.stock.domain.enums.SerialStatus;
import com.erp.modules.stock.repository.StockSerialRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serial number tracking service (ADR-0028 D-7, FR-INVD-23..27).
 *
 * <p>Integrity invariant (BR-INVD-11): count of IN_STOCK serials at a location for a product
 * equals the {@code stock_on_hand.quantity}. This service maintains the serial side;
 * the primary stock movement service maintains the on-hand side.
 */
@Service
@Transactional
public class StockSerialServiceImpl implements StockSerialService {

    private static final Logger log = LoggerFactory.getLogger(StockSerialServiceImpl.class);

    private final StockSerialRepository serials;
    private final ProductRepository     products;
    private final ScopeGuard            scopeGuard;

    public StockSerialServiceImpl(StockSerialRepository serials,
                                   ProductRepository products,
                                   ScopeGuard scopeGuard) {
        this.serials    = serials;
        this.products   = products;
        this.scopeGuard = scopeGuard;
    }

    // -------------------------------------------------------------------------
    // record
    // -------------------------------------------------------------------------

    @Override
    public StockSerialDto record(Long companyId, Long branchId, Long locationId, Long productId,
                                  String serialNumber, String receivedDocumentUid, Long actorId) {
        // Idempotency: if the serial already exists at this company+product, return it
        StockSerial existing = serials.findByCompanyIdAndProductIdAndSerialNumber(
                companyId, productId, serialNumber).orElse(null);
        if (existing != null) {
            return toDto(existing);
        }

        StockSerial serial = new StockSerial(
                companyId, branchId, locationId, productId,
                serialNumber, receivedDocumentUid, actorId);
        serials.save(serial);
        return toDto(serial);
    }

    // -------------------------------------------------------------------------
    // removeReceived (D-2 — Goods Receipt void symmetry)
    // -------------------------------------------------------------------------

    @Override
    public void removeReceived(Long companyId, Long productId, String serialNumber, String receiptUid) {
        StockSerial serial = serials.findByCompanyIdAndProductIdAndSerialNumber(
                companyId, productId, serialNumber).orElse(null);

        if (serial == null) {
            log.warn("StockSerial removeReceived: no serial found for serial='{}' product={} " +
                            "company={} receipt={} — skipping", serialNumber, productId, companyId, receiptUid);
            return;
        }
        if (serial.getSerialStatus() != SerialStatus.IN_STOCK) {
            log.warn("StockSerial removeReceived: serial='{}' product={} is {} (not IN_STOCK) — " +
                            "refusing to delete an already-moved serial (receipt={})",
                    serialNumber, productId, serial.getSerialStatus(), receiptUid);
            return;
        }
        if (serial.getReceivedDocumentUid() == null
                || !serial.getReceivedDocumentUid().equals(receiptUid)) {
            log.warn("StockSerial removeReceived: serial='{}' product={} was received by a different " +
                            "document (expected={}, actual={}) — skipping",
                    serialNumber, productId, receiptUid, serial.getReceivedDocumentUid());
            return;
        }

        serials.delete(serial);
        log.debug("StockSerial removeReceived: deleted serial='{}' product={} receipt={}",
                serialNumber, productId, receiptUid);
    }

    // -------------------------------------------------------------------------
    // issue
    // -------------------------------------------------------------------------

    @Override
    public StockSerialDto issue(String uid, String issuedDocumentUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockSerial serial = findAndAssertScope(uid, principal);

        if (serial.getSerialStatus() != SerialStatus.IN_STOCK) {
            throw new IllegalStateException(
                    "This serial number cannot be issued because it is not currently in stock "
                            + "(current status: " + serial.getSerialStatus() + ").");
        }
        serial.issue(issuedDocumentUid, principal.userId());
        serials.save(serial);
        return toDto(serial);
    }

    // -------------------------------------------------------------------------
    // returnSerial
    // -------------------------------------------------------------------------

    @Override
    public StockSerialDto returnSerial(String uid, Long locationId) {
        RequestContext.Principal principal = RequestContext.get();
        StockSerial serial = findAndAssertScope(uid, principal);

        if (serial.getSerialStatus() != SerialStatus.ISSUED) {
            throw new IllegalStateException(
                    "This serial number cannot be returned because it is not currently issued "
                            + "(current status: " + serial.getSerialStatus() + ").");
        }
        serial.returnSerial(locationId, principal.userId());
        serials.save(serial);
        return toDto(serial);
    }

    // -------------------------------------------------------------------------
    // restock
    // -------------------------------------------------------------------------

    @Override
    public StockSerialDto restock(String uid, Long locationId) {
        RequestContext.Principal principal = RequestContext.get();
        StockSerial serial = findAndAssertScope(uid, principal);

        if (serial.getSerialStatus() != SerialStatus.RETURNED) {
            throw new IllegalStateException(
                    "This serial number cannot be restocked because it is not in a returned state "
                            + "(current status: " + serial.getSerialStatus() + ").");
        }
        serial.restock(locationId, principal.userId());
        serials.save(serial);
        return toDto(serial);
    }

    // -------------------------------------------------------------------------
    // moveLocation
    // -------------------------------------------------------------------------

    @Override
    public StockSerialDto moveLocation(String uid, Long newLocationId) {
        RequestContext.Principal principal = RequestContext.get();
        StockSerial serial = findAndAssertScope(uid, principal);

        if (serial.getSerialStatus() == SerialStatus.ISSUED) {
            throw new IllegalStateException(
                    "This serial number cannot be moved to another location because it is currently issued.");
        }
        serial.moveLocation(newLocationId, principal.userId());
        serials.save(serial);
        return toDto(serial);
    }

    // -------------------------------------------------------------------------
    // reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public StockSerialDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        StockSerial serial = findAndAssertScope(uid, principal);
        return toDto(serial);
    }

    @Override
    @Transactional(readOnly = true)
    public StockSerialDto lookup(Long companyId, Long productId, String serialNumber) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        StockSerial serial = serials.findByCompanyIdAndProductIdAndSerialNumber(
                        companyId, productId, serialNumber)
                .orElseThrow(() -> new NotFoundException(
                        "Serial number '" + serialNumber + "' was not found for the specified product."));
        return toDto(serial);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockSerialDto> listAtLocation(Long companyId, Long locationId, Long productId,
                                                SerialStatus status, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        if (status != null) {
            return serials.findByCompanyIdAndLocationIdAndProductIdAndSerialStatus(
                            companyId, locationId, productId, status, pageable)
                    .map(StockSerialServiceImpl::toDto);
        }
        // status=null: fall back to IN_STOCK (the most common read-side use case);
        // callers that need all statuses should use listByProduct + filter client-side
        return serials.findByCompanyIdAndLocationIdAndProductIdAndSerialStatus(
                        companyId, locationId, productId, SerialStatus.IN_STOCK, pageable)
                .map(StockSerialServiceImpl::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockSerialDto> listByProduct(Long companyId, Long productId, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return serials.findByCompanyIdAndProductId(companyId, productId, pageable)
                .map(StockSerialServiceImpl::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockSerialDto> listByProductUid(Long companyId, String productUid,
                                                  Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        Long productId = products.findByCompanyIdAndUid(companyId, productUid)
                .map(p -> p.getId())
                .orElseThrow(() -> new NotFoundException("Product not found."));
        return serials.findByCompanyIdAndProductId(companyId, productId, pageable)
                .map(StockSerialServiceImpl::toDto);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StockSerial findAndAssertScope(String uid, RequestContext.Principal principal) {
        StockSerial s = serials.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StockSerial", uid));
        scopeGuard.assertCanActIn(principal, s.getCompanyId());
        return s;
    }

    private static StockSerialDto toDto(StockSerial s) {
        return new StockSerialDto(
                s.getId(), s.getUid(),
                s.getCompanyId(), s.getBranchId(), s.getLocationId(), s.getProductId(),
                s.getSerialNumber(), s.getSerialStatus(),
                s.getReceivedDocumentUid(), s.getIssuedDocumentUid(),
                s.getCreatedAt());
    }
}
