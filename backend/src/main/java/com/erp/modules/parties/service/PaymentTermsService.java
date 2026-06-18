package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreatePaymentTermsRequest;
import com.erp.modules.parties.domain.dto.PaymentTermsDto;
import com.erp.modules.parties.domain.dto.UpdatePaymentTermsRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** PaymentTerms master administration (D-2, ADR-0040). */
public interface PaymentTermsService {

    PaymentTermsDto create(CreatePaymentTermsRequest request);

    PaymentTermsDto getByUid(String uid);

    Page<PaymentTermsDto> list(Long companyId, String q, Pageable pageable);

    PaymentTermsDto updateByUid(String uid, UpdatePaymentTermsRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);
}
