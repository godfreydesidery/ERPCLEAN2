package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.ApPaymentDto.PaymentAllocationDto;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.ApPaymentKind;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApPaymentServiceImpl}:
 *  - Defect fix: toDto must resolve and populate supplierBillUid from the bill repository.
 *  - derivePaymentStatus transitions remain in ApPaymentOnAccountServiceImplTest.
 */
class ApPaymentServiceImplTest {

    // -------------------------------------------------------------------------
    // Defect: PaymentAllocationDto.supplierBillUid was always null
    // -------------------------------------------------------------------------

    @Test
    void toDto_allocationDto_populatesSupplierBillUid() {
        // Arrange — mock the payment header
        ApPayment payment = mock(ApPayment.class);
        when(payment.getId()).thenReturn(1L);
        when(payment.getUid()).thenReturn("PAY-UID-001");
        when(payment.getCompanyId()).thenReturn(10L);
        when(payment.getBranchId()).thenReturn(20L);
        when(payment.getSupplierId()).thenReturn(5L);
        when(payment.getPaymentNumber()).thenReturn("PAY-0001");
        when(payment.getKind()).thenReturn(ApPaymentKind.SINGLE);
        when(payment.getPaymentDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(payment.getAmount()).thenReturn(new BigDecimal("1000.00"));
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(payment.getCurrency()).thenReturn(currency);
        when(payment.getTenderType()).thenReturn("CASH");
        when(payment.getBankReference()).thenReturn(null);
        when(payment.getGlEntryUid()).thenReturn("JE-UID-001");
        when(payment.getWhtAmount()).thenReturn(null);
        when(payment.getWhtTransactionUid()).thenReturn(null);
        when(payment.getUnallocatedAmount()).thenReturn(BigDecimal.ZERO);
        when(payment.getStatus()).thenReturn(ApPaymentStatus.ALLOCATED);
        when(payment.getChequeUid()).thenReturn(null);
        when(payment.getPaymentRunId()).thenReturn(null);

        // Arrange — mock one allocation row with a known supplierBillId
        ApPaymentAllocation alloc = mock(ApPaymentAllocation.class);
        when(alloc.getId()).thenReturn(100L);
        when(alloc.getSupplierBillId()).thenReturn(42L);
        when(alloc.getAllocatedAmount()).thenReturn(new BigDecimal("1000.00"));

        // Arrange — mock the bill repository so uid is returned for billId 42
        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);
        SupplierBill bill = mock(SupplierBill.class);
        when(bill.getUid()).thenReturn("BILL-UID-042");
        when(billRepo.findByCompanyIdAndId(10L, 42L)).thenReturn(Optional.of(bill));

        // Act
        ApPaymentDto dto = ApPaymentServiceImpl.toDto(payment, List.of(alloc), billRepo);

        // Assert
        assertThat(dto.allocations()).hasSize(1);
        PaymentAllocationDto allocDto = dto.allocations().get(0);
        assertThat(allocDto.supplierBillId()).isEqualTo(42L);
        assertThat(allocDto.supplierBillUid())
                .as("supplierBillUid must be resolved from the repository (defect fix)")
                .isEqualTo("BILL-UID-042");
        assertThat(allocDto.allocatedAmount()).isEqualByComparingTo(new BigDecimal("1000.00"));
    }

    @Test
    void toDto_allocationDto_unknownBillId_yieldsNullUid() {
        // A stale / orphaned allocation whose bill no longer exists must not throw —
        // it returns null uid (graceful degradation).
        ApPayment payment = mock(ApPayment.class);
        when(payment.getId()).thenReturn(2L);
        when(payment.getUid()).thenReturn("PAY-UID-002");
        when(payment.getCompanyId()).thenReturn(10L);
        when(payment.getBranchId()).thenReturn(20L);
        when(payment.getSupplierId()).thenReturn(5L);
        when(payment.getPaymentNumber()).thenReturn("PAY-0002");
        when(payment.getKind()).thenReturn(ApPaymentKind.SINGLE);
        when(payment.getPaymentDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(payment.getAmount()).thenReturn(BigDecimal.TEN);
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(payment.getCurrency()).thenReturn(currency);
        when(payment.getTenderType()).thenReturn("CASH");
        when(payment.getBankReference()).thenReturn(null);
        when(payment.getGlEntryUid()).thenReturn(null);
        when(payment.getWhtAmount()).thenReturn(null);
        when(payment.getWhtTransactionUid()).thenReturn(null);
        when(payment.getUnallocatedAmount()).thenReturn(BigDecimal.ZERO);
        when(payment.getStatus()).thenReturn(ApPaymentStatus.ALLOCATED);
        when(payment.getChequeUid()).thenReturn(null);
        when(payment.getPaymentRunId()).thenReturn(null);

        ApPaymentAllocation alloc = mock(ApPaymentAllocation.class);
        when(alloc.getId()).thenReturn(200L);
        when(alloc.getSupplierBillId()).thenReturn(999L);
        when(alloc.getAllocatedAmount()).thenReturn(BigDecimal.TEN);

        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);
        when(billRepo.findByCompanyIdAndId(10L, 999L)).thenReturn(Optional.empty());

        ApPaymentDto dto = ApPaymentServiceImpl.toDto(payment, List.of(alloc), billRepo);

        assertThat(dto.allocations().get(0).supplierBillUid())
                .as("unknown bill id must yield null uid, not throw")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // UAT 2026-08: a posted payment must state WHICH cash/bank account it drew on
    // -------------------------------------------------------------------------

    /**
     * The live defect. {@code cashBankAccountUid} is optional on a payment run and falls back to the
     * company default, and the posted response said nothing at all about which account the money left
     * — so a run against the wrong account looked exactly like a correct one until reconciliation.
     */
    @Test
    void toDto_echoesTheCashBankAccountThePaymentDrewOn() {
        ApPayment payment = paymentHeader(3L, "PAY-UID-003", "PAY-0003");
        when(payment.getCashBankAccountId()).thenReturn(77L);

        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);
        ApPaymentDto dto = ApPaymentServiceImpl.toDto(payment, List.of(), billRepo,
                new ApPaymentServiceImpl.CashAccountRef(
                        77L, "CBA-UID-077", "Main NMB Current", "011010012345"));

        assertThat(dto.cashBankAccountId()).isEqualTo(77L);
        assertThat(dto.cashBankAccountUid()).isEqualTo("CBA-UID-077");
        assertThat(dto.cashBankAccountName())
                .as("the human label is what tells a reviewer which account this was")
                .isEqualTo("Main NMB Current");
        assertThat(dto.cashBankAccountNumber())
                .as("the number is what tells two accounts at the same bank apart")
                .isEqualTo("011010012345");
    }

    /**
     * When the account row cannot be read the response still says an account WAS chosen — the id
     * stamped on the payment row survives, so "which account?" degrades to "this one, unnamed"
     * rather than back to the original silence.
     */
    @Test
    void toDto_unresolvableAccount_stillReportsTheStampedAccountId() {
        ApPayment payment = paymentHeader(4L, "PAY-UID-004", "PAY-0004");
        when(payment.getCashBankAccountId()).thenReturn(77L);

        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);
        ApPaymentDto dto = ApPaymentServiceImpl.toDto(payment, List.of(), billRepo, null);

        assertThat(dto.cashBankAccountId()).isEqualTo(77L);
        assertThat(dto.cashBankAccountUid()).isNull();
        assertThat(dto.cashBankAccountName()).isNull();
        assertThat(dto.cashBankAccountNumber()).isNull();
    }

    // -------------------------------------------------------------------------

    /** A minimal payment header — every field {@code toDto} reads, nothing else. */
    private static ApPayment paymentHeader(Long id, String uid, String number) {
        ApPayment payment = mock(ApPayment.class);
        when(payment.getId()).thenReturn(id);
        when(payment.getUid()).thenReturn(uid);
        when(payment.getCompanyId()).thenReturn(10L);
        when(payment.getBranchId()).thenReturn(20L);
        when(payment.getSupplierId()).thenReturn(5L);
        when(payment.getPaymentNumber()).thenReturn(number);
        when(payment.getKind()).thenReturn(ApPaymentKind.PAYMENT_RUN);
        when(payment.getPaymentDate()).thenReturn(LocalDate.of(2026, 8, 12));
        when(payment.getAmount()).thenReturn(new BigDecimal("1000.00"));
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(payment.getCurrency()).thenReturn(currency);
        when(payment.getTenderType()).thenReturn("BANK_TRANSFER");
        when(payment.getBankReference()).thenReturn(null);
        when(payment.getGlEntryUid()).thenReturn("JE-UID-003");
        when(payment.getWhtAmount()).thenReturn(null);
        when(payment.getWhtTransactionUid()).thenReturn(null);
        when(payment.getUnallocatedAmount()).thenReturn(BigDecimal.ZERO);
        when(payment.getStatus()).thenReturn(ApPaymentStatus.ALLOCATED);
        when(payment.getChequeUid()).thenReturn(null);
        when(payment.getPaymentRunId()).thenReturn(9L);
        return payment;
    }
}
