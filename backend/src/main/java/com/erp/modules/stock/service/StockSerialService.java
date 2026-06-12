package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockSerialDto;
import com.erp.modules.stock.domain.enums.SerialStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Serial number tracking operations (ADR-0028 D-7, FR-INVD-23..27).
 *
 * <p>Each serial maps one-to-one with a physical unit. Status transitions:
 * IN_STOCK → ISSUED (on delivery/sale) → RETURNED → IN_STOCK (on restock).
 */
public interface StockSerialService {

    /**
     * Record a new serial as IN_STOCK at the given location.
     * Called by the receipt handler for serial-tracked products.
     *
     * @param companyId           tenant
     * @param branchId            branch
     * @param locationId          receiving location
     * @param productId           product
     * @param serialNumber        unique serial number within (company, product)
     * @param receivedDocumentUid source document uid (goods receipt uid)
     * @param actorId             user performing the action
     * @return DTO of the created serial record
     */
    StockSerialDto record(Long companyId, Long branchId, Long locationId, Long productId,
                           String serialNumber, String receivedDocumentUid, Long actorId);

    /**
     * Issue a serial (sale/delivery): ISSUED + clear location.
     *
     * @param uid                serial uid
     * @param issuedDocumentUid  the delivery / sale document uid
     */
    StockSerialDto issue(String uid, String issuedDocumentUid);

    /**
     * Return a serial: set RETURNED + restore location.
     *
     * @param uid        serial uid
     * @param locationId location to which the serial is returned
     */
    StockSerialDto returnSerial(String uid, Long locationId);

    /**
     * Restock a returned serial: back to IN_STOCK at given location.
     *
     * @param uid        serial uid
     * @param locationId stock location
     */
    StockSerialDto restock(String uid, Long locationId);

    /**
     * Move a serial to a new location (transfer — FR-INVD-26).
     *
     * @param uid           serial uid
     * @param newLocationId destination location id
     */
    StockSerialDto moveLocation(String uid, Long newLocationId);

    /**
     * Look up a serial by uid.
     */
    StockSerialDto getByUid(String uid);

    /**
     * Look up a serial by (company, product, serialNumber).
     */
    StockSerialDto lookup(Long companyId, Long productId, String serialNumber);

    /**
     * Paged list of serials at a location for a product (filtered by status if provided).
     *
     * @param status nullable — if null, all statuses are returned
     */
    Page<StockSerialDto> listAtLocation(Long companyId, Long locationId, Long productId,
                                         SerialStatus status, Pageable pageable);

    /**
     * Full history paged list by product (FR-INVD-27).
     */
    Page<StockSerialDto> listByProduct(Long companyId, Long productId, Pageable pageable);
}
