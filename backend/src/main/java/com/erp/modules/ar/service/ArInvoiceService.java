package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read operations on AR open items (ADR-0014 D-1). Creation is via the outbox handler. */
public interface ArInvoiceService {

    ArInvoiceDto getByUid(String uid);

    Page<ArInvoiceDto> listByCompany(Long companyId, Pageable pageable);

    Page<ArInvoiceDto> listByCustomer(Long companyId, Long customerId, Pageable pageable);
}
