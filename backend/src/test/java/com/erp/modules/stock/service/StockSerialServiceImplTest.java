package com.erp.modules.stock.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.stock.domain.entity.StockSerial;
import com.erp.modules.stock.repository.StockSerialRepository;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StockSerialServiceImpl}, focused on D-2 (Goods Receipt void serial
 * reversal): {@link StockSerialService#removeReceived}.
 */
class StockSerialServiceImplTest {

    private static final Long   COMPANY_ID  = 10L;
    private static final Long   BRANCH_ID   = 20L;
    private static final Long   LOCATION_ID = 30L;
    private static final Long   PRODUCT_ID  = 40L;
    private static final String RECEIPT_UID = "RCPT-0001";

    private StockSerialRepository serials;
    private StockSerialServiceImpl service;

    @BeforeEach
    void setUp() {
        serials = mock(StockSerialRepository.class);
        ProductRepository products = mock(ProductRepository.class);
        ScopeGuard scopeGuard = mock(ScopeGuard.class);
        service = new StockSerialServiceImpl(serials, products, scopeGuard);
    }

    // -------------------------------------------------------------------------
    // removeReceived
    // -------------------------------------------------------------------------

    @Test
    void removeReceived_inStockAndMatchingReceipt_deletesRow() {
        StockSerial serial = new StockSerial(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                "SN-001", RECEIPT_UID, 1L);
        when(serials.findByCompanyIdAndProductIdAndSerialNumber(COMPANY_ID, PRODUCT_ID, "SN-001"))
                .thenReturn(Optional.of(serial));

        service.removeReceived(COMPANY_ID, PRODUCT_ID, "SN-001", RECEIPT_UID);

        verify(serials).delete(serial);
    }

    @Test
    void removeReceived_noMatchingSerial_skipsSilently() {
        when(serials.findByCompanyIdAndProductIdAndSerialNumber(COMPANY_ID, PRODUCT_ID, "MISSING"))
                .thenReturn(Optional.empty());

        service.removeReceived(COMPANY_ID, PRODUCT_ID, "MISSING", RECEIPT_UID);

        verify(serials, never()).delete(any());
    }

    @Test
    void removeReceived_serialIssued_doesNotDelete() {
        StockSerial serial = new StockSerial(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                "SN-002", RECEIPT_UID, 1L);
        serial.issue("SI-9999", 1L); // moved on — now ISSUED
        when(serials.findByCompanyIdAndProductIdAndSerialNumber(COMPANY_ID, PRODUCT_ID, "SN-002"))
                .thenReturn(Optional.of(serial));

        service.removeReceived(COMPANY_ID, PRODUCT_ID, "SN-002", RECEIPT_UID);

        verify(serials, never()).delete(any());
    }

    @Test
    void removeReceived_differentReceivingDocument_doesNotDelete() {
        StockSerial serial = new StockSerial(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                "SN-003", "OTHER-RECEIPT", 1L);
        when(serials.findByCompanyIdAndProductIdAndSerialNumber(COMPANY_ID, PRODUCT_ID, "SN-003"))
                .thenReturn(Optional.of(serial));

        service.removeReceived(COMPANY_ID, PRODUCT_ID, "SN-003", RECEIPT_UID);

        verify(serials, never()).delete(any());
    }
}
