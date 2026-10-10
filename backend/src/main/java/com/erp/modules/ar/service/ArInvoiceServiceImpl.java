package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private final ArCustomerNames customerNames;

    public ArInvoiceServiceImpl(ArInvoiceRepository invoices, ScopeGuard scopeGuard,
                                ArDocumentNumberResolver documentNumbers,
                                ArCustomerNames customerNames) {
        this.invoices        = invoices;
        this.scopeGuard      = scopeGuard;
        this.documentNumbers = documentNumbers;
        this.customerNames   = customerNames;
    }

    @Override
    public ArInvoiceDto getByUid(String uid) {
        ArInvoice inv = Lookups.orNotFound(invoices.findByUid(uid), "ArInvoice", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());
        return customerNames.fillInvoices(inv.getCompanyId(),
                List.of(documentNumbers.fill(toDto(inv)))).get(0);
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

    @Override
    public Page<ArInvoiceDto> list(Long companyId, Long customerId, String customerUid,
                                   String status, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long custId = resolveCustomer(companyId, customerId, customerUid);
        Set<ArInvoiceStatus> statuses = parseStatuses(status);
        Page<ArInvoice> page;
        if (statuses.isEmpty()) {
            page = custId != null
                    ? invoices.findByCompanyIdAndCustomerId(companyId, custId, pageable)
                    : invoices.findByCompanyId(companyId, pageable);
        } else {
            page = custId != null
                    ? invoices.findByCompanyIdAndCustomerIdAndStatusIn(companyId, custId, statuses,
                            pageable)
                    : invoices.findByCompanyIdAndStatusIn(companyId, statuses, pageable);
        }
        return named(companyId, page.map(ArInvoiceServiceImpl::toDto));
    }

    @Override
    public List<ArInvoiceDto> listOpenForCustomer(Long companyId, Long customerId,
                                                  String customerUid) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long custId = resolveCustomer(companyId, customerId, customerUid);
        if (custId == null) {
            throw new IllegalArgumentException("Choose a customer.");
        }
        List<ArInvoiceDto> open = invoices.findOpenByCompanyAndCustomerOrderByDueDate(companyId, custId)
                .stream().map(ArInvoiceServiceImpl::toDto).toList();
        return customerNames.fillInvoices(companyId, documentNumbers.fill(companyId, open));
    }

    /**
     * The id form passes straight through; a uid is resolved inside the company (a uid from
     * another company is "not found"). Both blank = no customer filter.
     */
    private Long resolveCustomer(Long companyId, Long customerId, String customerUid) {
        if (customerUid != null && !customerUid.isBlank()) {
            return customerNames.idOf(companyId, customerUid);
        }
        return customerId;
    }

    /** {@code OPEN}, {@code open,partial}, blank → the status set; an unknown word is a 400. */
    static Set<ArInvoiceStatus> parseStatuses(String status) {
        Set<ArInvoiceStatus> out = EnumSet.noneOf(ArInvoiceStatus.class);
        if (status == null || status.isBlank()) {
            return out;
        }
        for (String part : status.split(",")) {
            String s = part.trim().toUpperCase(Locale.ROOT);
            if (s.isEmpty()) continue;
            try {
                out.add(ArInvoiceStatus.valueOf(s));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Unknown invoice status. Choose Open, Partial, Paid or Written off.");
            }
        }
        return out;
    }

    /** The page with each item's document number and customer name filled in. */
    private Page<ArInvoiceDto> named(Long companyId, Page<ArInvoiceDto> page) {
        List<ArInvoiceDto> rows = customerNames.fillInvoices(companyId,
                documentNumbers.fill(companyId, page.getContent()));
        return new PageImpl<>(rows, page.getPageable(), page.getTotalElements());
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
