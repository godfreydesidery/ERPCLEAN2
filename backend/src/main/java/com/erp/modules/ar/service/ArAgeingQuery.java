package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArCustomerAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArStatementDto;
import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.AgeingBucket;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes ageing and customer statement on demand (ADR-0014 D-7, FR-AR-08/12).
 * Not stored — computed from ar_invoices filtered by status IN (OPEN,PARTIAL).
 *
 * <p>Every amount is in the open item's OWN currency and is grouped by that currency: an invoice
 * raised in USD is never added to TZS ones under the TZS label. Converting to base was rejected:
 * the only persisted rate is the open item's booked {@code fx_rate} / {@code
 * base_outstanding_amount}, and V62 back-filled every pre-existing row at rate 1 (base = face)
 * whatever its currency, so for older foreign items the stored base figure IS the face amount and
 * converting with it would repeat this very defect.
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
    private final ArDocumentNumberResolver documentNumbers;

    public ArAgeingQuery(ArInvoiceRepository invoices,
                          ArReceiptRepository receipts,
                          ArReceiptAllocationRepository allocations,
                          CompanyRepository companies,
                          CustomerRepository customers,
                          ScopeGuard scopeGuard,
                          ArDocumentNumberResolver documentNumbers) {
        this.documentNumbers = documentNumbers;
        this.invoices    = invoices;
        this.receipts    = receipts;
        this.allocations = allocations;
        this.companies   = companies;
        this.customers   = customers;
        this.scopeGuard  = scopeGuard;
    }

    /**
     * Ageing breakdown for a customer (or company-wide when {@code customerId} is null) as at a
     * given date.  Passing {@code customerId=null} returns the aggregate across all customers for
     * the company — used by the AR Ageing screen when no specific customer is selected (bug #5 fix).
     */
    public List<ArAgeingRowDto> ageing(Long companyId, Long customerId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));

        List<ArInvoice> openItems = (customerId != null)
                ? invoices.findOpenForStatement(companyId, customerId)
                : invoices.findOpenForCompany(companyId);

        // One five-bucket block PER CURRENCY: an open item is aged in its own currency and is never
        // added to an amount in another one. The base-currency block always comes first and is
        // always present (all zero when nothing is open), so a single-currency company sees exactly
        // the five rows it always did; each foreign currency adds its own five rows after it.
        Map<String, Map<AgeingBucket, BigDecimal>> byCurrency = new TreeMap<>(baseFirst(currency));
        byCurrency.put(currency, zeroBuckets());
        for (ArInvoice inv : openItems) {
            AgeingBucket bucket = classify(inv.getDueDate(), asAt);
            byCurrency.computeIfAbsent(currencyOf(inv, currency), c -> zeroBuckets())
                    .merge(bucket, inv.getOutstandingAmount(), BigDecimal::add);
        }

        List<ArAgeingRowDto> rows = new ArrayList<>();
        for (Map.Entry<String, Map<AgeingBucket, BigDecimal>> e : byCurrency.entrySet()) {
            for (AgeingBucket bk : AgeingBucket.values()) {
                rows.add(new ArAgeingRowDto(bk, e.getValue().get(bk), e.getKey()));
            }
        }
        return rows;
    }

    /** Full customer statement as at a given date. */
    public ArStatementDto statement(Long companyId, Long customerId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));

        List<ArInvoice> openItems = invoices.findOpenForStatement(companyId, customerId);
        List<ArInvoiceDto> openDtos = documentNumbers.fill(companyId, openItems.stream()
                .map(ArInvoiceServiceImpl::toDto).toList());

        // Outstanding per currency (base first). totalOutstanding is labelled with the base
        // currency, so it is the base-currency open items only; a foreign invoice is counted in
        // totalsByCurrency under its own code, never added in at par.
        Map<String, BigDecimal> totalsByCurrency = new TreeMap<>(baseFirst(currency));
        totalsByCurrency.put(currency, BigDecimal.ZERO);
        for (ArInvoice inv : openItems) {
            totalsByCurrency.merge(currencyOf(inv, currency), inv.getOutstandingAmount(),
                    BigDecimal::add);
        }
        BigDecimal total = totalsByCurrency.get(currency);

        List<ArAgeingRowDto> ageingRows = ageing(companyId, customerId, asAt);

        // Recent receipts (last 10)
        var recentReceipts = receipts.findRecentByCompanyAndCustomer(
                companyId, customerId, PageRequest.of(0, 10));
        List<ArReceiptDto> receiptDtos = recentReceipts.stream()
                .map(r -> ArReceiptServiceImpl.toDto(r, allocations.findByReceiptId(r.getId()), invoices))
                .toList();

        return new ArStatementDto(companyId, customerId, asAt, total, currency,
                ageingRows, openDtos, receiptDtos, new LinkedHashMap<>(totalsByCurrency));
    }

    /**
     * Per-customer ageing breakdown for the AR Ageing screen (CFO credit-limit view).
     * Unlike {@link #ageing(Long, Long, LocalDate)} (a 5-row company-wide bucket summary), this
     * returns one row per customer that has open items, each carrying all five bucket amounts.
     */
    public List<ArCustomerAgeingRowDto> customerAgeing(Long companyId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));

        List<ArInvoice> openItems = invoices.findOpenForCompany(companyId);

        // One row per (customer, currency): a customer owing in two currencies gets two rows, each
        // in its own currency; the amounts are never added together under one label.
        Map<CustomerCurrency, Map<AgeingBucket, BigDecimal>> byCustomer = new LinkedHashMap<>();
        for (ArInvoice inv : openItems) {
            Map<AgeingBucket, BigDecimal> buckets = byCustomer.computeIfAbsent(
                    new CustomerCurrency(inv.getCustomerId(), currencyOf(inv, currency)),
                    k -> new EnumMap<>(AgeingBucket.class));
            AgeingBucket bucket = classify(inv.getDueDate(), asAt);
            buckets.merge(bucket, inv.getOutstandingAmount(), BigDecimal::add);
        }

        List<ArCustomerAgeingRowDto> rows = new ArrayList<>();
        for (Map.Entry<CustomerCurrency, Map<AgeingBucket, BigDecimal>> entry : byCustomer.entrySet()) {
            Long custId = entry.getKey().customerId();
            Map<AgeingBucket, BigDecimal> buckets = entry.getValue();

            BigDecimal current  = buckets.getOrDefault(AgeingBucket.CURRENT,  BigDecimal.ZERO);
            BigDecimal d1to30   = buckets.getOrDefault(AgeingBucket.D1_30,    BigDecimal.ZERO);
            BigDecimal d31to60  = buckets.getOrDefault(AgeingBucket.D31_60,   BigDecimal.ZERO);
            BigDecimal d61to90  = buckets.getOrDefault(AgeingBucket.D61_90,   BigDecimal.ZERO);
            BigDecimal d91plus  = buckets.getOrDefault(AgeingBucket.D90_PLUS, BigDecimal.ZERO);
            BigDecimal total    = current.add(d1to30).add(d31to60).add(d61to90).add(d91plus);

            Optional<Customer> customer = customers.findById(custId);
            String code = customer.map(Customer::getCode).orElse(null);
            String name = customer.map(Customer::getDisplayName).orElse(null);

            rows.add(new ArCustomerAgeingRowDto(custId, code, name,
                    current, d1to30, d31to60, d61to90, d91plus, total, entry.getKey().currency()));
        }

        rows.sort(Comparator
                .comparing(ArCustomerAgeingRowDto::customerName,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ArCustomerAgeingRowDto::customerCode,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ArCustomerAgeingRowDto::currency, baseFirst(currency)));

        return rows;
    }

    /**
     * uid-based overload for the documents module (ADR-0023 D-5 / AR_STATEMENT render).
     * Resolves customerUid → internal id, then delegates to statement(companyId, customerId, asAt).
     * Additive — no interface change needed.
     */
    public ArStatementDto statementByCustomerUid(Long companyId, String customerUid, LocalDate asAt) {
        Long customerId = customers.findByUid(customerUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found."));
        return statement(companyId, customerId, asAt);
    }

    // -------------------------------------------------------------------------

    /** Grouping key of the per-customer ageing: one row per customer per currency. */
    private record CustomerCurrency(Long customerId, String currency) {}

    /** The open item's own currency; an item with none recorded is in the company's base. */
    static String currencyOf(ArInvoice inv, String baseCurrency) {
        return inv.getCurrency() != null ? inv.getCurrency().value() : baseCurrency;
    }

    /** Base currency first, then the others A to Z: the order of every per-currency list. */
    static Comparator<String> baseFirst(String baseCurrency) {
        return Comparator.comparing((String c) -> !c.equals(baseCurrency))
                .thenComparing(Comparator.naturalOrder());
    }

    private static Map<AgeingBucket, BigDecimal> zeroBuckets() {
        Map<AgeingBucket, BigDecimal> buckets = new EnumMap<>(AgeingBucket.class);
        for (AgeingBucket b : AgeingBucket.values()) buckets.put(b, BigDecimal.ZERO);
        return buckets;
    }

    private static AgeingBucket classify(LocalDate dueDate, LocalDate asAt) {
        long daysOverdue = ChronoUnit.DAYS.between(dueDate, asAt);
        if (daysOverdue <= 0)  return AgeingBucket.CURRENT;
        if (daysOverdue <= 30) return AgeingBucket.D1_30;
        if (daysOverdue <= 60) return AgeingBucket.D31_60;
        if (daysOverdue <= 90) return AgeingBucket.D61_90;
        return AgeingBucket.D90_PLUS;
    }
}
