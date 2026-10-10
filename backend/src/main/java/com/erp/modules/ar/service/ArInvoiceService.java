package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Read operations on AR open items (ADR-0014 D-1). Creation is via the outbox handler. */
public interface ArInvoiceService {

    ArInvoiceDto getByUid(String uid);

    Page<ArInvoiceDto> listByCompany(Long companyId, Pageable pageable);

    Page<ArInvoiceDto> listByCustomer(Long companyId, Long customerId, Pageable pageable);

    /**
     * The Receivables list with its filters (ARC-02). The customer may be named by id or by uid
     * (a uid is resolved inside {@code companyId}); both null = every customer. {@code status} is
     * one status or a comma-separated list (e.g. {@code OPEN,PARTIAL}); blank = every status.
     */
    Page<ArInvoiceDto> list(Long companyId, Long customerId, String customerUid, String status,
                            Pageable pageable);

    /**
     * Every OPEN / PARTIAL item of one customer, oldest due date first — the Record Receipt
     * allocation grid (ARC-02). Not paged: a receipt must be able to reach every open item.
     */
    List<ArInvoiceDto> listOpenForCustomer(Long companyId, Long customerId, String customerUid);
}
