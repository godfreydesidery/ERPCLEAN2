package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for D-9 AP on-account behaviour:
 *  1. derivePaymentStatus transitions (UNALLOCATED / PARTIAL / ALLOCATED).
 *  2. ApBalanceServiceImpl nets unallocated payments from outstanding bills.
 */
class ApPaymentOnAccountServiceImplTest {

    private SupplierBillRepository billRepo;
    private ApPaymentRepository    paymentRepo;
    private CompanyRepository      companyRepo;
    private ScopeGuard             scopeGuard;
    private ApBalanceServiceImpl   balanceService;

    @BeforeEach
    void setUp() {
        billRepo    = mock(SupplierBillRepository.class);
        paymentRepo = mock(ApPaymentRepository.class);
        companyRepo = mock(CompanyRepository.class);
        scopeGuard  = mock(ScopeGuard.class);
        balanceService = new ApBalanceServiceImpl(billRepo, paymentRepo, companyRepo, scopeGuard);

        RequestContext.set(new RequestContext.Principal(1L, "user", false, 10L, 20L, null));

        Company company = mock(Company.class);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companyRepo.findById(10L)).thenReturn(Optional.of(company));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // derivePaymentStatus
    // -------------------------------------------------------------------------

    @Test
    void derivePaymentStatus_fullUnallocated_returnsUNALLOCATED() {
        BigDecimal amount    = new BigDecimal("1000.00");
        BigDecimal allocated = BigDecimal.ZERO;
        BigDecimal remaining = amount;

        ApPaymentStatus status = ApPaymentServiceImpl.derivePaymentStatus(remaining, amount, allocated);

        assertThat(status).isEqualTo(ApPaymentStatus.UNALLOCATED);
    }

    @Test
    void derivePaymentStatus_partiallyAllocated_returnsPARTIAL() {
        BigDecimal amount    = new BigDecimal("1000.00");
        BigDecimal allocated = new BigDecimal("600.00");
        BigDecimal remaining = new BigDecimal("400.00");

        ApPaymentStatus status = ApPaymentServiceImpl.derivePaymentStatus(remaining, amount, allocated);

        assertThat(status).isEqualTo(ApPaymentStatus.PARTIAL);
    }

    @Test
    void derivePaymentStatus_fullyAllocated_returnsALLOCATED() {
        BigDecimal amount    = new BigDecimal("1000.00");
        BigDecimal allocated = new BigDecimal("1000.00");
        BigDecimal remaining = BigDecimal.ZERO;

        ApPaymentStatus status = ApPaymentServiceImpl.derivePaymentStatus(remaining, amount, allocated);

        assertThat(status).isEqualTo(ApPaymentStatus.ALLOCATED);
    }

    // -------------------------------------------------------------------------
    // ApBalanceServiceImpl — D-9 netting
    // -------------------------------------------------------------------------

    @Test
    void currentBalance_netsUnallocatedFromOutstanding() {
        // Outstanding bills: 5000; on-account remainder: 1200 → net balance 3800
        when(billRepo.sumOutstandingBySupplier(10L, 5L)).thenReturn(new BigDecimal("5000.00"));
        when(paymentRepo.sumUnallocatedByCompanyAndSupplier(10L, 5L)).thenReturn(new BigDecimal("1200.00"));

        ApBalanceDto dto = balanceService.currentBalance(10L, 5L);

        assertThat(dto.outstandingBalance())
                .as("balance must net out the on-account prepayment")
                .isEqualByComparingTo(new BigDecimal("3800.00"));
        assertThat(dto.currency()).isEqualTo("TZS");
    }

    @Test
    void currentBalance_noUnallocated_returnsFullOutstanding() {
        when(billRepo.sumOutstandingBySupplier(10L, 5L)).thenReturn(new BigDecimal("2000.00"));
        when(paymentRepo.sumUnallocatedByCompanyAndSupplier(10L, 5L)).thenReturn(BigDecimal.ZERO);

        ApBalanceDto dto = balanceService.currentBalance(10L, 5L);

        assertThat(dto.outstandingBalance()).isEqualByComparingTo(new BigDecimal("2000.00"));
    }

    @Test
    void currentBalance_unallocatedExceedsOutstanding_returnsNegative() {
        // Prepayment > outstanding → credit balance (negative)
        when(billRepo.sumOutstandingBySupplier(10L, 5L)).thenReturn(new BigDecimal("500.00"));
        when(paymentRepo.sumUnallocatedByCompanyAndSupplier(10L, 5L)).thenReturn(new BigDecimal("800.00"));

        ApBalanceDto dto = balanceService.currentBalance(10L, 5L);

        assertThat(dto.outstandingBalance())
                .as("supplier has a net credit (prepayment > outstanding)")
                .isEqualByComparingTo(new BigDecimal("-300.00"));
    }
}
