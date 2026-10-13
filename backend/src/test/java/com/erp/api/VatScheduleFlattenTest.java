package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.tax.domain.dto.VatScheduleDto;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/** RPT-14 / PAR-06: the VAT schedule export layout. */
class VatScheduleFlattenTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 10, 9, 0, 0, 0, ZoneOffset.UTC);

    @Test
    void salesSchedule_hasCustomerTinVrnAndEfdColumns_andPlainNumbers() {
        VatScheduleDto dto = new VatScheduleDto("VATR-0001", 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), VatReturnStatus.FILED,
                List.of(
                        new VatScheduleDto.Row(LocalDate.of(2026, 9, 3), "INVOICE", "INV-1", null,
                                "Mbasha Ltd", "100-200-300", "40-001234-A",
                                new BigDecimal("1234567.5"), new BigDecimal("222222.15"), "EFD-77"),
                        new VatScheduleDto.Row(LocalDate.of(2026, 9, 9), "CREDIT_NOTE", "CN-1", null,
                                "Mbasha Ltd", "100-200-300", null,
                                new BigDecimal("-100"), new BigDecimal("-18"), null)),
                new BigDecimal("1234467.5"), new BigDecimal("222204.15"));

        TabularRenderModel m = VatReturnController.flattenSchedule(dto, true, null, NOW);

        assertThat(m.columns()).extracting(TabularRenderModel.Column::header)
                .containsExactly("Date", "Type", "Invoice / Note No", "Customer", "TIN", "VRN",
                        "Net Amount", "VAT", "EFD / Fiscal No");
        assertThat(m.rows().get(0)).containsExactly("2026-09-03", "Invoice", "INV-1", "Mbasha Ltd",
                "100-200-300", "40-001234-A", "1234567.50", "222222.15", "EFD-77");
        assertThat(m.rows().get(1)).contains("Credit note", "-100.00", "-18.00");
        assertThat(m.totalsRow()).containsExactly("Total (2)", "", "", "", "", "",
                "1234467.50", "222204.15", "");
    }

    @Test
    void purchasesSchedule_carriesSupplierInvoiceNoAndOurRef() {
        VatScheduleDto dto = new VatScheduleDto("VATR-0001", 1L,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), VatReturnStatus.DRAFT,
                List.of(new VatScheduleDto.Row(LocalDate.of(2026, 9, 5), "BILL", "SI-9", "BILL-0042",
                        "Supplier Co", "111", "222", new BigDecimal("2000"), new BigDecimal("360"), null)),
                new BigDecimal("2000"), new BigDecimal("360"));

        TabularRenderModel m = VatReturnController.flattenSchedule(dto, false, null, NOW);

        assertThat(m.columns()).extracting(TabularRenderModel.Column::header)
                .containsExactly("Date", "Type", "Supplier Invoice No", "Our Ref", "Supplier", "TIN",
                        "VRN", "Net Amount", "VAT");
        assertThat(m.rows().get(0)).containsExactly("2026-09-05", "Bill", "SI-9", "BILL-0042",
                "Supplier Co", "111", "222", "2000.00", "360.00");
        assertThat(m.headerLines()).anyMatch(l -> l.contains("DRAFT"));
    }
}
