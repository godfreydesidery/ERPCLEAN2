package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupplierBillService {

    /** Enter a supplier bill (DRAFT, no GL post). AP.BILL.ENTER. */
    SupplierBillDto enterBill(EnterBillRequest req);

    SupplierBillDto getByUid(String uid);

    Page<SupplierBillDto> listByCompany(Long companyId, Pageable pageable);

    Page<SupplierBillDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable);
}
