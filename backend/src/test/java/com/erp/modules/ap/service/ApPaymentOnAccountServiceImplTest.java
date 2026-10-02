package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.ApBalanceDto;
import com.erp.modules.ap.domain.dto.ApUnconvertedAmountDto;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
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

    private ApFxSplitQuery         fxSplit;
    private CompanyRepository      companyRepo;
    private ScopeGuard             scopeGuard;
    private ApBalanceServiceImpl   balanceService;

    @BeforeEach
    void setUp() {
        fxSplit     = mock(ApFxSplitQuery.class);
        companyRepo = mock(CompanyRepository.class);
        scopeGuard  = mock(ScopeGuard.class);
        balanceService = new ApBalanceServiceImpl(fxSplit, companyRepo, scopeGuard);

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
    // ApBalanceServiceImpl — the netting (Σ outstanding − Σ unallocated − Σ unapplied DN) now
    // runs in ApFxSplitQuery's SQL (owner ruling 2026-10-02: base currency, reliable rows only)
    // and is proven on real Postgres by ApFxBalanceIT; here only the pass-through is checked.
    // -------------------------------------------------------------------------

    @Test
    void currentBalance_returnsBaseTotalAndUnconvertedFromTheSplit() {
        when(fxSplit.split(10L, 5L)).thenReturn(new ApFxSplitQuery.Split(
                new BigDecimal("-300.00"),
                List.of(new ApUnconvertedAmountDto("USD", new BigDecimal("40.00"), 1))));

        ApBalanceDto dto = balanceService.currentBalance(10L, 5L);

        assertThat(dto.outstandingBalance()).isEqualByComparingTo(new BigDecimal("-300.00"));
        assertThat(dto.currency()).isEqualTo("TZS");
        assertThat(dto.unconverted()).singleElement()
                .satisfies(u -> {
                    assertThat(u.currency()).isEqualTo("USD");
                    assertThat(u.amount()).isEqualByComparingTo("40.00");
                });
    }
}
