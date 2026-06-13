package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockSerialDto;
import com.erp.modules.stock.domain.entity.StockSerial;
import com.erp.modules.stock.domain.enums.SerialStatus;
import com.erp.modules.stock.repository.StockSerialRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
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

    private final StockSerialRepository serials;
    private final ScopeGuard            scopeGuard;

    public StockSerialServiceImpl(StockSerialRepository serials,
                                   ScopeGuard scopeGuard) {
        this.serials    = serials;
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
    // issue
    // -------------------------------------------------------------------------

    @Override
    public StockSerialDto issue(String uid, String issuedDocumentUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockSerial serial = findAndAssertScope(uid, principal);

        if (serial.getSerialStatus() != SerialStatus.IN_STOCK) {
            throw new IllegalStateException(
                    "Serial " + uid + " is not IN_STOCK (status=" + serial.getSerialStatus() + ").");
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
                    "Serial " + uid + " is not ISSUED (status=" + serial.getSerialStatus() + ").");
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
                    "Serial " + uid + " is not RETURNED (status=" + serial.getSerialStatus() + ").");
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
                    "Cannot move serial " + uid + " — it is currently ISSUED.");
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
                        "No serial '" + serialNumber + "' for product " + productId +
                        " in company " + companyId));
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
