package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto.AllocationDto;
import com.erp.modules.ar.domain.entity.ArCreditNote;
import com.erp.modules.ar.domain.entity.ArCreditNoteAllocation;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArCreditNoteStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArCreditNoteServiceImpl}:
 *  - Defect fix: AllocationDto.arInvoiceUid was always null (numeric id was present).
 *    toDto must resolve and populate the uid from the invoice repository.
 */
class ArCreditNoteServiceImplTest {

    // -------------------------------------------------------------------------
    // Defect: AllocationDto.arInvoiceUid was always null
    // -------------------------------------------------------------------------

    @Test
    void toDto_allocationDto_populatesArInvoiceUid() {
        // Arrange — credit note header mock
        ArCreditNote note = mock(ArCreditNote.class);
        when(note.getId()).thenReturn(1L);
        when(note.getUid()).thenReturn("CN-UID-001");
        when(note.getCompanyId()).thenReturn(10L);
        when(note.getCustomerId()).thenReturn(7L);
        when(note.getCreditNoteNumber()).thenReturn("CN-0001");
        when(note.getArInvoiceId()).thenReturn(55L);
        when(note.getNoteDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(note.getAmount()).thenReturn(new BigDecimal("300.00"));
        when(note.getNetAmount()).thenReturn(new BigDecimal("260.87"));
        when(note.getVatAmount()).thenReturn(new BigDecimal("39.13"));
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("USD");
        when(note.getCurrency()).thenReturn(currency);
        when(note.getReason()).thenReturn("Return of goods");
        when(note.getOrigin()).thenReturn(ArCreditNoteOrigin.STANDALONE);
        when(note.getGlEntryUid()).thenReturn("JE-UID-CN-001");
        when(note.getUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getBaseAmount()).thenReturn(new BigDecimal("720000.00"));
        when(note.getBaseUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getFxRate()).thenReturn(new BigDecimal("2400.00"));
        when(note.getStatus()).thenReturn(ArCreditNoteStatus.APPLIED);

        // Arrange — one allocation row targeting invoice 55
        ArCreditNoteAllocation alloc = mock(ArCreditNoteAllocation.class);
        when(alloc.getId()).thenReturn(200L);
        when(alloc.getArInvoiceId()).thenReturn(55L);
        when(alloc.getAllocatedAmount()).thenReturn(new BigDecimal("300.00"));

        // Arrange — invoice repository resolves uid for id 55
        ArInvoiceRepository invoiceRepo = mock(ArInvoiceRepository.class);
        ArInvoice invoice = mock(ArInvoice.class);
        when(invoice.getUid()).thenReturn("INV-UID-055");
        when(invoiceRepo.findById(55L)).thenReturn(Optional.of(invoice));

        // Act
        ArCreditNoteDto dto = ArCreditNoteServiceImpl.toDto(note, List.of(alloc), invoiceRepo);

        // Assert
        assertThat(dto.allocations()).hasSize(1);
        AllocationDto allocDto = dto.allocations().get(0);
        assertThat(allocDto.arInvoiceId()).isEqualTo(55L);
        assertThat(allocDto.arInvoiceUid())
                .as("arInvoiceUid must be resolved from the repository (defect fix)")
                .isEqualTo("INV-UID-055");
        assertThat(allocDto.allocatedAmount()).isEqualByComparingTo(new BigDecimal("300.00"));
    }

    @Test
    void toDto_nullAllocations_returnsEmptyList() {
        ArCreditNote note = mock(ArCreditNote.class);
        when(note.getId()).thenReturn(2L);
        when(note.getUid()).thenReturn("CN-UID-002");
        when(note.getCompanyId()).thenReturn(10L);
        when(note.getCustomerId()).thenReturn(7L);
        when(note.getCreditNoteNumber()).thenReturn("CN-0002");
        when(note.getArInvoiceId()).thenReturn(null);
        when(note.getNoteDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(note.getAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getNetAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getVatAmount()).thenReturn(BigDecimal.ZERO);
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(note.getCurrency()).thenReturn(currency);
        when(note.getReason()).thenReturn("Standalone CN");
        when(note.getOrigin()).thenReturn(ArCreditNoteOrigin.STANDALONE);
        when(note.getGlEntryUid()).thenReturn(null);
        when(note.getUnappliedAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getBaseAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getBaseUnappliedAmount()).thenReturn(new BigDecimal("100.00"));
        when(note.getFxRate()).thenReturn(BigDecimal.ONE);
        when(note.getStatus()).thenReturn(ArCreditNoteStatus.UNAPPLIED);

        ArInvoiceRepository invoiceRepo = mock(ArInvoiceRepository.class);

        ArCreditNoteDto dto = ArCreditNoteServiceImpl.toDto(note, null, invoiceRepo);

        assertThat(dto.allocations())
                .as("null allocation list must produce empty list, not NPE")
                .isEmpty();
    }

    @Test
    void toDto_allocationDto_unknownInvoiceId_yieldsNullUid() {
        ArCreditNote note = mock(ArCreditNote.class);
        when(note.getId()).thenReturn(3L);
        when(note.getUid()).thenReturn("CN-UID-003");
        when(note.getCompanyId()).thenReturn(10L);
        when(note.getCustomerId()).thenReturn(7L);
        when(note.getCreditNoteNumber()).thenReturn("CN-0003");
        when(note.getArInvoiceId()).thenReturn(88L);
        when(note.getNoteDate()).thenReturn(LocalDate.of(2026, 6, 1));
        when(note.getAmount()).thenReturn(new BigDecimal("50.00"));
        when(note.getNetAmount()).thenReturn(new BigDecimal("50.00"));
        when(note.getVatAmount()).thenReturn(BigDecimal.ZERO);
        CurrencyCode currency = mock(CurrencyCode.class);
        when(currency.value()).thenReturn("TZS");
        when(note.getCurrency()).thenReturn(currency);
        when(note.getReason()).thenReturn("Test");
        when(note.getOrigin()).thenReturn(ArCreditNoteOrigin.STANDALONE);
        when(note.getGlEntryUid()).thenReturn(null);
        when(note.getUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getBaseAmount()).thenReturn(new BigDecimal("50.00"));
        when(note.getBaseUnappliedAmount()).thenReturn(BigDecimal.ZERO);
        when(note.getFxRate()).thenReturn(BigDecimal.ONE);
        when(note.getStatus()).thenReturn(ArCreditNoteStatus.APPLIED);

        ArCreditNoteAllocation alloc = mock(ArCreditNoteAllocation.class);
        when(alloc.getId()).thenReturn(400L);
        when(alloc.getArInvoiceId()).thenReturn(888L);
        when(alloc.getAllocatedAmount()).thenReturn(new BigDecimal("50.00"));

        ArInvoiceRepository invoiceRepo = mock(ArInvoiceRepository.class);
        when(invoiceRepo.findById(888L)).thenReturn(Optional.empty());

        ArCreditNoteDto dto = ArCreditNoteServiceImpl.toDto(note, List.of(alloc), invoiceRepo);

        assertThat(dto.allocations().get(0).arInvoiceUid())
                .as("unknown invoice id must yield null uid, not throw")
                .isNull();
    }
}
