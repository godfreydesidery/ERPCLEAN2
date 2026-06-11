package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArStatementDto;
import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.AgeingBucket;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes ageing and customer statement on demand (ADR-0014 D-7, FR-AR-08/12).
 * Not stored — computed from ar_invoices filtered by status IN (OPEN,PARTIAL).
 */
@Component
@Transactional(readOnly = true)
public class ArAgeingQuery {

    private final ArInvoiceRepository invoices;
    private final ArReceiptRepository receipts;
    private final ArReceiptAllocationRepository allocations;
    private final CompanyRepository companies;
    private final CustomerRepository customers;
    private final ScopeGuard scopeGuard;

    public ArAgeingQuery(ArInvoiceRepository invoices,
                          ArReceiptRepository receipts,
                          ArReceiptAllocationRepository allocations,
                          CompanyRepository companies,
                          CustomerRepository customers,
                          ScopeGuard scopeGuard) {
        this.invoices    = invoices;
        this.receipts    = receipts;
        this.allocations = allocations;
        this.companies   = companies;
        this.customers   = customers;
        this.scopeGuard  = scopeGuard;
    }

    /** Ageing breakdown for a customer as at a given date. */
    public List<ArAgeingRowDto> ageing(Long companyId, Long customerId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        List<ArInvoice> openItems = invoices.findOpenForStatement(companyId, customerId);

        Map<AgeingBucket, BigDecimal> buckets = new EnumMap<>(AgeingBucket.class);
        for (AgeingBucket b : AgeingBucket.values()) buckets.put(b, BigDecimal.ZERO);

        for (ArInvoice inv : openItems) {
            AgeingBucket bucket = classify(inv.getDueDate(), asAt);
            buckets.merge(bucket, inv.getOutstandingAmount(), BigDecimal::add);
        }

        List<ArAgeingRowDto> rows = new ArrayList<>();
        for (AgeingBucket b : AgeingBucket.values()) {
            rows.add(new ArAgeingRowDto(b, buckets.get(b), currency));
        }
        return rows;
    }

    /** Full customer statement as at a given date. */
    public ArStatementDto statement(Long companyId, Long customerId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        List<ArInvoice> openItems = invoices.findOpenForStatement(companyId, customerId);
        List<ArInvoiceDto> openDtos = openItems.stream()
                .map(ArInvoiceServiceImpl::toDto).toList();

        BigDecimal total = openItems.stream()
                .map(ArInvoice::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ArAgeingRowDto> ageingRows = ageing(companyId, customerId, asAt);

        // Recent receipts (last 10)
        var recentReceipts = receipts.findRecentByCompanyAndCustomer(
                companyId, customerId, PageRequest.of(0, 10));
        List<ArReceiptDto> receiptDtos = recentReceipts.stream()
                .map(r -> ArReceiptServiceImpl.toDto(r, allocations.findByReceiptId(r.getId()), invoices))
                .toList();

        return new ArStatementDto(companyId, customerId, asAt, total, currency,
                ageingRows, openDtos, receiptDtos);
    }

    /**
     * uid-based overload for the documents module (ADR-0023 D-5 / AR_STATEMENT render).
     * Resolves customerUid → internal id, then delegates to statement(companyId, customerId, asAt).
     * Additive — no interface change needed.
     */
    public ArStatementDto statementByCustomerUid(Long companyId, String customerUid, LocalDate asAt) {
        Long customerId = customers.findByUid(customerUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found: " + customerUid));
        return statement(companyId, customerId, asAt);
    }

    // -------------------------------------------------------------------------

    private static AgeingBucket classify(LocalDate dueDate, LocalDate asAt) {
        long daysOverdue = ChronoUnit.DAYS.between(dueDate, asAt);
        if (daysOverdue <= 0)  return AgeingBucket.CURRENT;
        if (daysOverdue <= 30) return AgeingBucket.D1_30;
        if (daysOverdue <= 60) return AgeingBucket.D31_60;
        if (daysOverdue <= 90) return AgeingBucket.D61_90;
        return AgeingBucket.D90_PLUS;
    }
}
