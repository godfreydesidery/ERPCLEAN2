package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ApPaymentService {

    /** Pay a single bill (fully or partly). Posts DR AP / CR Cash synchronously. AP.PAYMENT.RUN. */
    ApPaymentDto paySingle(PaySingleBillRequest req);

    /** Run a payment run: select due bills, pay them all. Posts one GL entry. AP.PAYMENT.RUN. */
    ApPaymentDto paymentRun(PaymentRunRequest req);

    ApPaymentDto getByUid(String uid);

    Page<ApPaymentDto> listByCompany(Long companyId, Pageable pageable);

    Page<ApPaymentDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable);
}
