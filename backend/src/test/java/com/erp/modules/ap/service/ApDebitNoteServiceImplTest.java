package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.ApDebitNoteDto.AllocationDto;
import com.erp.modules.ap.domain.entity.ApDebitNote;
import com.erp.modules.ap.domain.entity.ApDebitNoteAllocation;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.ApDebitNoteStatus;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ApDebitNoteServiceImpl}:
 *  - Defect fix: AllocationDto.supplierBillUid was always null (numeric id was present).
 *    toDto must resolve and populate the uid from the bill repository.
 */
class ApDebitNoteServiceImplTest {

    // -------------------------------------------------------------------------
    // Defect: AllocationDto.supplierBillUid was always null
    // -------------------------------------------------------------------------

    @Test
    void toDto_allocationDto_populatesSupplierBillUid() {
        // Arrange — debit note header mock
        ApDebitNote note = mock(ApDebitNote.class);
        when(note.getId()).thenReturn(1L);
        when(note.getUid()).thenReturn("DN-UID-001");
        when(note.getCompanyId()).thenReturn(10L);
        when(note.getBranchId()).thenReturn(20L);
        when(note.getSupplierId()).thenReturn(5L);
        when(note.getDebitNoteNumber()).thenReturn("DN-0001");
        when(note.getSupplierBillId()).thenReturn(42L);
        when(note.getNoteDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(note.getAmount()).thenReturn(new BigDecimal("500.00"));
        when(note.getNetAmount()).thenReturn(new BigDecimal("434.78"));
        when(note.getVatAmount()).thenReturn(new BigDecimal("65.22"));
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(note.getCurrency()).thenReturn(currency);
        when(note.getReason()).thenReturn("Defective goods");
        when(note.getGlEntryUid()).thenReturn("JE-UID-DN-001");
        when(note.getOrigin()).thenReturn("STANDALONE");
        when(note.getOriginRef()).thenReturn(null);
        when(note.getUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getBaseAmount()).thenReturn(new BigDecimal("500.00"));
        when(note.getBaseUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getFxRate()).thenReturn(BigDecimal.ONE);
        when(note.getStatus()).thenReturn(ApDebitNoteStatus.APPLIED);

        // Arrange — one allocation row targeting bill 42
        ApDebitNoteAllocation alloc = mock(ApDebitNoteAllocation.class);
        when(alloc.getId()).thenReturn(100L);
        when(alloc.getSupplierBillId()).thenReturn(42L);
        when(alloc.getAllocatedAmount()).thenReturn(new BigDecimal("500.00"));

        // Arrange — bill repository resolves uid for id 42
        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);
        SupplierBill bill = mock(SupplierBill.class);
        when(bill.getUid()).thenReturn("BILL-UID-042");
        when(billRepo.findByCompanyIdAndId(10L, 42L)).thenReturn(Optional.of(bill));

        // Act
        ApDebitNoteDto dto = ApDebitNoteServiceImpl.toDto(note, List.of(alloc), billRepo);

        // Assert
        assertThat(dto.allocations()).hasSize(1);
        AllocationDto allocDto = dto.allocations().get(0);
        assertThat(allocDto.supplierBillId()).isEqualTo(42L);
        assertThat(allocDto.supplierBillUid())
                .as("supplierBillUid must be resolved from the repository (defect fix)")
                .isEqualTo("BILL-UID-042");
        assertThat(allocDto.allocatedAmount()).isEqualByComparingTo(new BigDecimal("500.00"));
    }

    @Test
    void toDto_nullAllocations_returnsEmptyList() {
        ApDebitNote note = mock(ApDebitNote.class);
        when(note.getId()).thenReturn(2L);
        when(note.getUid()).thenReturn("DN-UID-002");
        when(note.getCompanyId()).thenReturn(10L);
        when(note.getBranchId()).thenReturn(20L);
        when(note.getSupplierId()).thenReturn(5L);
        when(note.getDebitNoteNumber()).thenReturn("DN-0002");
        when(note.getSupplierBillId()).thenReturn(null);
        when(note.getNoteDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(note.getAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getNetAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getVatAmount()).thenReturn(BigDecimal.ZERO);
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(note.getCurrency()).thenReturn(currency);
        when(note.getReason()).thenReturn("Test");
        when(note.getGlEntryUid()).thenReturn(null);
        when(note.getOrigin()).thenReturn("STANDALONE");
        when(note.getOriginRef()).thenReturn(null);
        when(note.getUnappliedAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getBaseAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getBaseUnappliedAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getFxRate()).thenReturn(BigDecimal.ONE);
        when(note.getStatus()).thenReturn(ApDebitNoteStatus.UNAPPLIED);

        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);

        ApDebitNoteDto dto = ApDebitNoteServiceImpl.toDto(note, null, billRepo);

        assertThat(dto.allocations())
                .as("null allocation list must produce empty list, not NPE")
                .isEmpty();
    }

    @Test
    void toDto_allocationDto_unknownBillId_yieldsNullUid() {
        ApDebitNote note = mock(ApDebitNote.class);
        when(note.getId()).thenReturn(3L);
        when(note.getUid()).thenReturn("DN-UID-003");
        when(note.getCompanyId()).thenReturn(10L);
        when(note.getBranchId()).thenReturn(20L);
        when(note.getSupplierId()).thenReturn(5L);
        when(note.getDebitNoteNumber()).thenReturn("DN-0003");
        when(note.getSupplierBillId()).thenReturn(99L);
        when(note.getNoteDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(note.getAmount()).thenReturn(new BigDecimal("200.00"));
        when(note.getNetAmount()).thenReturn(new BigDecimal("200.00"));
        when(note.getVatAmount()).thenReturn(BigDecimal.ZERO);
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(note.getCurrency()).thenReturn(currency);
        when(note.getReason()).thenReturn("Test");
        when(note.getGlEntryUid()).thenReturn(null);
        when(note.getOrigin()).thenReturn("STANDALONE");
        when(note.getOriginRef()).thenReturn(null);
        when(note.getUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getBaseAmount()).thenReturn(new BigDecimal("200.00"));
        when(note.getBaseUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getFxRate()).thenReturn(BigDecimal.ONE);
        when(note.getStatus()).thenReturn(ApDebitNoteStatus.APPLIED);

        ApDebitNoteAllocation alloc = mock(ApDebitNoteAllocation.class);
        when(alloc.getId()).thenReturn(300L);
        when(alloc.getSupplierBillId()).thenReturn(999L);
        when(alloc.getAllocatedAmount()).thenReturn(new BigDecimal("200.00"));

        SupplierBillRepository billRepo = mock(SupplierBillRepository.class);
        when(billRepo.findByCompanyIdAndId(10L, 999L)).thenReturn(Optional.empty());

        ApDebitNoteDto dto = ApDebitNoteServiceImpl.toDto(note, List.of(alloc), billRepo);

        assertThat(dto.allocations().get(0).supplierBillUid())
                .as("unknown bill id must yield null uid, not throw")
                .isNull();
    }
}
