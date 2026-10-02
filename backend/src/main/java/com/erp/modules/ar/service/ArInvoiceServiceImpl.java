package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ArInvoiceServiceImpl implements ArInvoiceService {

    private final ArInvoiceRepository invoices;
    private final ScopeGuard scopeGuard;
    private final ArDocumentNumberResolver documentNumbers;

    public ArInvoiceServiceImpl(ArInvoiceRepository invoices, ScopeGuard scopeGuard,
                                ArDocumentNumberResolver documentNumbers) {
        this.invoices        = invoices;
        this.scopeGuard      = scopeGuard;
        this.documentNumbers = documentNumbers;
    }

    @Override
    public ArInvoiceDto getByUid(String uid) {
        ArInvoice inv = Lookups.orNotFound(invoices.findByUid(uid), "ArInvoice", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        return documentNumbers.fill(toDto(inv));
    }

    @Override
    public Page<ArInvoiceDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return named(companyId, invoices.findByCompanyId(companyId, pageable)
                .map(ArInvoiceServiceImpl::toDto));
    }

    @Override
    public Page<ArInvoiceDto> listByCustomer(Long companyId, Long customerId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return named(companyId, invoices.findByCompanyIdAndCustomerId(companyId, customerId, pageable)
                .map(ArInvoiceServiceImpl::toDto));
    }

    /** The page with each credit sale's missing document number read from its sales invoice. */
    private Page<ArInvoiceDto> named(Long companyId, Page<ArInvoiceDto> page) {
        return new PageImpl<>(documentNumbers.fill(companyId, page.getContent()),
                page.getPageable(), page.getTotalElements());
    }

    // -------------------------------------------------------------------------

    public static ArInvoiceDto toDto(ArInvoice i) {
        return new ArInvoiceDto(
                i.getId(), i.getUid(), i.getCompanyId(), i.getBranchId(), i.getCustomerId(),
                i.getSource(), i.getSourceInvoiceUid(), i.getDocumentNo(),
                i.getOriginalAmount(), i.getOutstandingAmount(), i.getCurrency().value(),
                i.getInvoiceDate(), i.getDueDate(),
                i.getSettlementDiscountDueDate(), i.getSettlementDiscountAmount(),
                i.getStatus());
    }
}
