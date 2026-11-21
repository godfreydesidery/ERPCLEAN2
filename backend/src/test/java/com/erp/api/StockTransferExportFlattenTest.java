package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.documents.service.DocumentBrandingService;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.reporting.service.ReportCompanyHeaderQuery;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.domain.dto.StockTransferLineDto;
import com.erp.modules.stock.domain.enums.StockTransferMode;
import com.erp.modules.stock.domain.enums.StockTransferStatus;
import com.erp.modules.stock.service.StockTransferService;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The printed / exported stock transfer and the V116 entered (reference) unit cost.
 *
 * <p>The entered pair sits BESIDE the system figures and never replaces them; when it is present
 * both pairs are labelled so neither is read as the other, and when no line has one the document
 * prints exactly as it did before.
 */
class StockTransferExportFlattenTest {

    private StockTransferController controller;

    @BeforeEach
    void setUp() {
        CompanyCalendar calendar = mock(CompanyCalendar.class);
        when(calendar.now(any())).thenReturn(
                ZonedDateTime.of(2026, 11, 21, 9, 30, 0, 0, ZoneId.of("Africa/Dar_es_Salaam")));
        controller = new StockTransferController(mock(StockTransferService.class),
                mock(TabularExporter.class), mock(ReportCompanyHeaderQuery.class),
                mock(DocumentBrandingService.class), calendar);
        RequestContext.set(new RequestContext.Principal(
                1L, "storekeeper", false, 1L, 10L, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void withoutAnEnteredCost_theDocumentKeepsItsOriginalSixColumns() {
        TabularRenderModel m = controller.flatten(transfer(List.of(
                line("P001", "2", "18000.0000", "36000.0000", null, null))), null, null);

        assertThat(m.columns()).extracting(Column::header).containsExactly(
                "Code", "Product Description", "Package", "Qty", "Price", "Total Amount");
        assertThat(m.rows().get(0)).containsExactly(
                "P001", "Konyagi 500ml", "Carton", "2", "18000.00", "36000.00");
        assertThat(m.totalsRow()).hasSize(6);
        assertThat(String.join("\n", m.footerLines())).doesNotContain("Entered");
    }

    @Test
    void withAnEnteredCost_bothPairsAreLabelledAndTheMissingOneIsBlankNotZero() {
        TabularRenderModel m = controller.flatten(transfer(List.of(
                line("P001", "2", "18000.0000", "36000.0000", "20000.0000", "40000.0000"),
                line("P002", "3", null, null, null, null))), null, null);

        assertThat(m.columns()).extracting(Column::header).containsExactly(
                "Code", "Product Description", "Package", "Qty",
                "System Price", "System Amount", "Entered Price", "Entered Amount");
        assertThat(m.rows().get(0)).containsExactly(
                "P001", "Konyagi 500ml", "Carton", "2", "18000.00", "36000.00", "20000.00", "40000.00");
        // Not entered and never costed both print blank — never 0.00.
        assertThat(m.rows().get(1)).containsExactly(
                "P002", "Konyagi 500ml", "Carton", "3", "", "", "", "");
        assertThat(m.totalsRow()).containsExactly(
                "", "Total Amount", "", "5", "", "36000.00", "", "40000.00");

        String footer = String.join("\n", m.footerLines());
        assertThat(footer).contains("for reference only", "valued at the System Price");
        assertThat(footer).contains("1 of 2 lines have no entered cost");
    }

    private static StockTransferDto transfer(List<StockTransferLineDto> lines) {
        return new StockTransferDto(700L, "STUID00000000000000000001", 1L, "TRF-0001",
                StockTransferStatus.DRAFT, StockTransferMode.INSTANT,
                10L, "Dar", "DAR", 100L, "Main Store",
                20L, "Arusha", "ARU", 200L, "Bar Counter",
                LocalDate.of(2026, 11, 21), null, null, null, null, null, null,
                lines, null, null);
    }

    private static StockTransferLineDto line(String code, String qty, String unitCost,
                                             String value, String entered, String enteredValue) {
        return new StockTransferLineDto(1L, "STLUID0000000000000000001", (short) 1,
                5L, code, "Konyagi 500ml", "Carton",
                new BigDecimal(qty), new BigDecimal(qty),
                dec(unitCost), dec(value), "TZS", dec(entered), dec(enteredValue));
    }

    private static BigDecimal dec(String s) {
        return s != null ? new BigDecimal(s) : null;
    }
}
