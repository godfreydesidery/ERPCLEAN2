package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.stock.domain.dto.StockReportDto;
import com.erp.modules.stock.service.StockReportQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The Stock Report must say which branch it covers — on the screen AND on the exported page
 * (UAT, 2026-08).
 *
 * <p><b>The defect.</b> A branch manager passed {@code branchUid} and got a 200 whose body carried no
 * branch identity of any kind, so a branch listing and a company-wide one were indistinguishable —
 * on figures that get signed off every morning. A downloaded copy was worse: it outlives the screen
 * that produced it, and nothing on the page said what it was a listing OF.
 *
 * <p>The exporter is mocked so this stays a fast surefire test; the renderers themselves are covered
 * by {@code TabularExporterTest}.
 */
class StockReportBranchScopeTest {

    private StockReportQuery      query;
    private TabularExporter       exporter;
    private StockReportController controller;
    private ArgumentCaptor<TabularRenderModel> captor;

    @BeforeEach
    void setUp() {
        query      = mock(StockReportQuery.class);
        exporter   = mock(TabularExporter.class);
        controller = new StockReportController(query, exporter);
        captor     = ArgumentCaptor.forClass(TabularRenderModel.class);

        when(exporter.export(any(), any()))
                .thenReturn(new ExportResult(new byte[]{1}, "application/pdf", "report.pdf"));
        RequestContext.set(new RequestContext.Principal(1L, "tester", true, 5L, 9L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void export_branchFiltered_printsTheBranchNameOnThePage() {
        when(query.report(anyLong(), eq("BR-UID-KILI")))
                .thenReturn(report("BR-UID-KILI", "Kilimanjaro", "Kilimanjaro"));

        controller.exportStockReport(ExportFormat.PDF, "BR-UID-KILI");

        verify(exporter).export(captor.capture(), any());
        assertThat(captor.getValue().headerLines())
                .as("a printed register has to name the branch it is a register of")
                .contains("Branch: Kilimanjaro");
    }

    /**
     * The half that is easy to forget: silence is the bug. A company-wide listing states that it is
     * company-wide, rather than leaving the reader to infer it from a field that is not there.
     */
    @Test
    void export_noBranchFilter_saysAllBranchesOutLoud() {
        when(query.report(anyLong(), eq(null)))
                .thenReturn(report(null, null, "All branches"));

        controller.exportStockReport(ExportFormat.PDF, null);

        verify(exporter).export(captor.capture(), any());
        assertThat(captor.getValue().headerLines()).contains("Branch: All branches");
    }

    @Test
    void screen_echoesTheBranchIdentityBackToTheCaller() {
        when(query.report(anyLong(), eq("BR-UID-KILI")))
                .thenReturn(report("BR-UID-KILI", "Kilimanjaro", "Kilimanjaro"));

        StockReportDto dto = controller.stockReport("BR-UID-KILI");

        assertThat(dto.branchUid()).isEqualTo("BR-UID-KILI");
        assertThat(dto.branchName()).isEqualTo("Kilimanjaro");
        assertThat(dto.branchLabel()).isEqualTo("Kilimanjaro");
    }

    // -------------------------------------------------------------------------

    private static StockReportDto report(String branchUid, String branchName, String branchLabel) {
        ReportCompanyHeaderDto company = new ReportCompanyHeaderDto(
                "Tembo Group", null, null, null, null, null, null, null, null, null, null);
        return new StockReportDto(company, branchUid, branchName, branchLabel,
                "TZS", List.of(), BigDecimal.ZERO, "2026-08-12T06:00:00Z");
    }
}
