package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupplierBillService {

    /** Enter a supplier bill (DRAFT, no GL post). AP.BILL.ENTER. */
    SupplierBillDto enterBill(EnterBillRequest req);

    SupplierBillDto getByUid(String uid);

    Page<SupplierBillDto> listByCompany(Long companyId, Pageable pageable);

    Page<SupplierBillDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable);

    /**
     * The bill list read, filtered.
     *
     * <p>Every filter is optional; each is applied in the database so paging and totals stay honest.
     *
     * @param supplierUid    supplier to restrict to, by uid; ignored when {@code supplierId} is set
     * @param status         bill status to restrict to
     * @param uncomparedOnly keep only bills whose lines were not ALL checked against a purchase
     *                       order and a goods receipt — the period-end review of payables that
     *                       posted on nobody's comparison
     */
    Page<SupplierBillDto> search(Long companyId,
                                 Long supplierId,
                                 String supplierUid,
                                 SupplierBillStatus status,
                                 boolean uncomparedOnly,
                                 Pageable pageable);
}
