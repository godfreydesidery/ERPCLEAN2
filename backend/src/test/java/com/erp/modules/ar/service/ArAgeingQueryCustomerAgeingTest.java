package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ar.domain.dto.ArCustomerAgeingRowDto;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link ArAgeingQuery#customerAgeing(Long, LocalDate)} — the per-customer AR
 * Ageing screen fix (CFO defect: bucket-only summary showed blank/all-zero rows; the screen
 * needs one row per customer to set credit limits).
 */
class ArAgeingQueryCustomerAgeingTest {

    private ArInvoiceRepository invoices;
    private CustomerRepository customers;
    private CompanyRepository companies;
    private ScopeGuard scopeGuard;
    private ArAgeingQuery query;

    private static final Long COMPANY_ID = 10L;
    private static final LocalDate AS_AT = LocalDate.of(2026, 7, 3);

    @BeforeEach
    void setUp() {
        invoices    = mock(ArInvoiceRepository.class);
        ArReceiptRepository receipts       = mock(ArReceiptRepository.class);
        ArReceiptAllocationRepository allocations = mock(ArReceiptAllocationRepository.class);
        companies   = mock(CompanyRepository.class);
        customers   = mock(CustomerRepository.class);
        scopeGuard  = mock(ScopeGuard.class);

        query = new ArAgeingQuery(invoices, receipts, allocations, companies, customers, scopeGuard);

        RequestContext.set(new RequestContext.Principal(
                1L, "user@test.com", false, COMPANY_ID, 20L, null));

        Company company = mock(Company.class);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void customerAgeing_twoCustomersAcrossBuckets_returnsOneRowPerCustomerWithCorrectSums() {
        Long cust1Id = 101L;
        Long cust2Id = 202L;

        // Customer 1: two invoices — one CURRENT, one D31_60
        ArInvoice invCurrent = invoiceOf(cust1Id, new BigDecimal("1000.00"), AS_AT);
        ArInvoice invD31_60  = invoiceOf(cust1Id, new BigDecimal("500.00"), AS_AT.minusDays(45));

        // Customer 2: one invoice in D90_PLUS
        ArInvoice invD90Plus = invoiceOf(cust2Id, new BigDecimal("2500.00"), AS_AT.minusDays(120));

        when(invoices.findOpenForCompany(COMPANY_ID))
                .thenReturn(List.of(invCurrent, invD31_60, invD90Plus));

        Customer cust1 = mockCustomer(cust1Id, "CUST-0001", "Alpha Traders");
        Customer cust2 = mockCustomer(cust2Id, "CUST-0002", "Beta Supplies");
        when(customers.findById(cust1Id)).thenReturn(Optional.of(cust1));
        when(customers.findById(cust2Id)).thenReturn(Optional.of(cust2));

        List<ArCustomerAgeingRowDto> rows = query.customerAgeing(COMPANY_ID, AS_AT);

        assertThat(rows).hasSize(2);

        // Ordered by customerName: Alpha Traders before Beta Supplies
        ArCustomerAgeingRowDto row1 = rows.get(0);
        ArCustomerAgeingRowDto row2 = rows.get(1);

        assertThat(row1.customerId()).isEqualTo(cust1Id);
        assertThat(row1.customerCode()).isEqualTo("CUST-0001");
        assertThat(row1.customerName()).isEqualTo("Alpha Traders");
        assertThat(row1.current()).isEqualByComparingTo("1000.00");
        assertThat(row1.days1to30()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row1.days31to60()).isEqualByComparingTo("500.00");
        assertThat(row1.days61to90()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row1.days91Plus()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row1.total()).isEqualByComparingTo("1500.00");
        assertThat(row1.currency()).isEqualTo("TZS");

        assertThat(row2.customerId()).isEqualTo(cust2Id);
        assertThat(row2.customerCode()).isEqualTo("CUST-0002");
        assertThat(row2.customerName()).isEqualTo("Beta Supplies");
        assertThat(row2.current()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row2.days1to30()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row2.days31to60()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row2.days61to90()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(row2.days91Plus()).isEqualByComparingTo("2500.00");
        assertThat(row2.total()).isEqualByComparingTo("2500.00");
        assertThat(row2.currency()).isEqualTo("TZS");
    }

    @Test
    void customerAgeing_missingCustomerRow_doesNotThrow_returnsNullCodeAndName() {
        Long custId = 303L;
        ArInvoice inv = invoiceOf(custId, new BigDecimal("750.00"), AS_AT);
        when(invoices.findOpenForCompany(COMPANY_ID)).thenReturn(List.of(inv));
        when(customers.findById(custId)).thenReturn(Optional.empty());

        List<ArCustomerAgeingRowDto> rows = query.customerAgeing(COMPANY_ID, AS_AT);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).customerId()).isEqualTo(custId);
        assertThat(rows.get(0).customerCode()).isNull();
        assertThat(rows.get(0).customerName()).isNull();
        assertThat(rows.get(0).current()).isEqualByComparingTo("750.00");
        assertThat(rows.get(0).total()).isEqualByComparingTo("750.00");
    }

    @Test
    void customerAgeing_noOpenItems_returnsEmptyList() {
        when(invoices.findOpenForCompany(COMPANY_ID)).thenReturn(List.of());

        List<ArCustomerAgeingRowDto> rows = query.customerAgeing(COMPANY_ID, AS_AT);

        assertThat(rows).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ArInvoice invoiceOf(Long customerId, BigDecimal outstanding, LocalDate dueDate) {
        ArInvoice inv = mock(ArInvoice.class);
        when(inv.getCustomerId()).thenReturn(customerId);
        when(inv.getOutstandingAmount()).thenReturn(outstanding);
        when(inv.getDueDate()).thenReturn(dueDate);
        return inv;
    }

    private static Customer mockCustomer(Long id, String code, String displayName) {
        Customer c = mock(Customer.class);
        when(c.getId()).thenReturn(id);
        when(c.getCode()).thenReturn(code);
        when(c.getDisplayName()).thenReturn(displayName);
        return c;
    }
}
