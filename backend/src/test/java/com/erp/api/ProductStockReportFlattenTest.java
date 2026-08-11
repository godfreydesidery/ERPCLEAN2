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
import com.erp.modules.stock.domain.dto.ProductStockReportDto;
import com.erp.modules.stock.domain.dto.ProductStockRowDto;
import com.erp.modules.stock.domain.dto.ProductStockTotalsDto;
import com.erp.modules.stock.service.ProductStockReportQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * What the two exports actually put on the page.
 *
 * <p>Three things the customer asked for, or would notice immediately if they broke:
 * <ol>
 *   <li>the Product List carries NO totals row — it is a catalogue, and a sum across unrelated
 *       products is a number with no meaning;</li>
 *   <li>the Stock Value report carries a totals row whose cost and sale cells line up with the Cost
 *       Value and Sale Value columns — a totals row one cell out of step is worse than none;</li>
 *   <li>an unknown price renders BLANK, never {@code 0.00}. "0.00" reads as "free"; blank reads as
 *       "nobody has costed it", which is the truth.</li>
 * </ol>
 *
 * <p>The exporter is mocked so this stays a fast surefire test — the renderers themselves are
 * covered by {@code TabularExporterTest}, including the null-totals case this relies on.
 */
class ProductStockReportFlattenTest {

    private static final int PRODUCT_LIST_COLUMNS = 6;
    private static final int STOCK_VALUE_COLUMNS  = 8;

    /** Index of the Cost Value / Sale Value cells in the Stock Value row and totals row. */
    private static final int COST_VALUE_CELL = 5;
    private static final int SALE_VALUE_CELL = 7;

    /** Qty On Hand sits in the same column on both reports, in the rows and in the totals row. */
    private static final int QTY_CELL = 3;

    private ProductStockReportQuery        query;
    private TabularExporter                exporter;
    private ProductStockReportController   controller;
    private ArgumentCaptor<TabularRenderModel> captor;

    @BeforeEach
    void setUp() {
        query    = mock(ProductStockReportQuery.class);
        exporter = mock(TabularExporter.class);
        controller = new ProductStockReportController(query, exporter);
        captor = ArgumentCaptor.forClass(TabularRenderModel.class);

        when(exporter.export(any(), any()))
                .thenReturn(new ExportResult(new byte[]{1}, "application/pdf", "report.pdf"));
        RequestContext.set(new RequestContext.Principal(1L, "tester", true, 5L, 9L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Product List — a catalogue, deliberately without a grand total
    // -------------------------------------------------------------------------

    @Test
    void productListExport_carriesNoTotalsRow() {
        stubReport(false, report(null, pricedRow()));

        controller.exportProductList(ExportFormat.PDF, null, null);

        TabularRenderModel model = capture();
        assertThat(model.totalsRow())
                .as("the Product List is a catalogue: a grand total across unrelated products is "
                        + "a number with no meaning, and the customer asked for it to be absent")
                .isNull();
        assertThat(model.columns()).hasSize(PRODUCT_LIST_COLUMNS);
        assertThat(model.rows()).allSatisfy(r -> assertThat(r).hasSize(PRODUCT_LIST_COLUMNS));
    }

    @Test
    void productListExport_rowsAreNeverShortOfCells() {
        stubReport(false, report(null, pricedRow(), blankRow()));

        controller.exportProductList(ExportFormat.CSV, null, null);

        assertThat(capture().rows()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // Stock Value — the totals row must line up with the money columns
    // -------------------------------------------------------------------------

    @Test
    void stockValueExport_totalsRowAlignsWithTheMoneyColumns() {
        stubReport(true, report(totals("6000.00", "9200.00"), pricedRow()));

        controller.exportStockValue(ExportFormat.PDF, null, null);

        TabularRenderModel model = capture();
        assertThat(model.columns()).hasSize(STOCK_VALUE_COLUMNS);
        assertThat(model.totalsRow()).isNotNull().hasSize(STOCK_VALUE_COLUMNS);
        assertThat(model.columns().get(COST_VALUE_CELL).header()).isEqualTo("Cost Value");
        assertThat(model.columns().get(SALE_VALUE_CELL).header()).isEqualTo("Sale Value");
        assertThat(model.totalsRow().get(COST_VALUE_CELL)).isEqualTo("6,000.00");
        assertThat(model.totalsRow().get(SALE_VALUE_CELL)).isEqualTo("9,200.00");
    }

    // -------------------------------------------------------------------------
    // "Unknown is not zero", all the way to the printed cell
    // -------------------------------------------------------------------------

    @Test
    void anUncostedUnpricedRow_rendersBlankCellsNotZeroes() {
        stubReport(true, report(totals("0", "0"), blankRow()));

        controller.exportStockValue(ExportFormat.XLSX, null, null);

        List<String> row = capture().rows().get(0);
        assertThat(row.get(4)).as("buying price").isEmpty();
        assertThat(row.get(COST_VALUE_CELL)).as("cost value").isEmpty();
        assertThat(row.get(6)).as("selling price").isEmpty();
        assertThat(row.get(SALE_VALUE_CELL)).as("sale value").isEmpty();
        assertThat(row).as("0.00 would read as 'this stock is free'").doesNotContain("0.00");
    }

    @Test
    void aRowWithNoSupplier_rendersAPlaceholderRatherThanFailing() {
        stubReport(false, report(null, blankRow()));

        // List.of() rejects nulls outright, so a missing supplier must be substituted, not passed on.
        controller.exportProductList(ExportFormat.PDF, null, null);

        assertThat(capture().rows().get(0).get(2)).isEqualTo("—");
    }

    // -------------------------------------------------------------------------
    // Quantities — weighed goods are ordinary stock, not an edge case
    // -------------------------------------------------------------------------

    /**
     * {@code stock_on_hand.quantity} is {@code NUMERIC(19,6)} and the shop weighs things. Rounding
     * the export to whole units printed 12.5 kg as "13" beside an unrounded cost value — arithmetic
     * the reader cannot reproduce — and printed 0.4 kg as "0", which reads as out of stock.
     */
    @Test
    void aFractionalQuantity_keepsItsDecimalsInTheRowCell() {
        stubReport(true, report(totals("12500.00", "18000.00"), weighedRow("12.500")));

        controller.exportStockValue(ExportFormat.PDF, null, null);

        assertThat(capture().rows().get(0).get(QTY_CELL))
                .as("12.5 kg is not 13 kg, and the cost value beside it was never rounded")
                .isEqualTo("12.500");
    }

    @Test
    void aQuantityBelowOne_doesNotRenderAsZero() {
        stubReport(false, report(null, weighedRow("0.400")));

        controller.exportProductList(ExportFormat.CSV, null, null);

        assertThat(capture().rows().get(0).get(QTY_CELL))
                .as("\"0\" on a stock report reads as out of stock — the shop still has 400 g")
                .isEqualTo("0.400");
    }

    /** The foot is where a rounded quantity is least forgivable: it is the figure people quote. */
    @Test
    void aFractionalGrandTotal_keepsItsDecimalsToo() {
        ProductStockTotalsDto t = new ProductStockTotalsDto(
                new BigDecimal("12.900"), new BigDecimal("6000.00"), new BigDecimal("9200.00"),
                new BigDecimal("3200.00"), 0, 0, 0);
        stubReport(true, report(t, weighedRow("12.500")));

        controller.exportStockValue(ExportFormat.XLSX, null, null);

        assertThat(capture().totalsRow().get(QTY_CELL)).isEqualTo("12.900");
    }

    @Test
    void aWholeQuantity_stillPrintsWithoutDecimals() {
        stubReport(true, report(totals("6000.00", "9200.00"), pricedRow()));

        controller.exportStockValue(ExportFormat.PDF, null, null);

        assertThat(capture().rows().get(0).get(QTY_CELL))
                .as("a shop counting tins does not want to read 10.000")
                .isEqualTo("10");
    }

    // -------------------------------------------------------------------------
    // Header disclosures — the page has to say what the numbers do and do not cover
    // -------------------------------------------------------------------------

    /**
     * An unfiltered run covers every branch in the company, including branches the caller is not
     * assigned to. "All branches" alone is read by some as "the branches I work in".
     */
    @Test
    void headerSaysAnUnfilteredRunIsCompanyWide() {
        stubReport(false, report(null, pricedRow()));

        controller.exportProductList(ExportFormat.PDF, null, null);

        assertThat(capture().headerLines())
                .anyMatch(l -> l.startsWith("Branch: ") && l.contains("whole company"));
    }

    @Test
    void headerSaysTheSellingPriceIsAListPriceNotATillQuote() {
        stubReport(false, report(null, pricedRow()));

        controller.exportProductList(ExportFormat.PDF, null, null);

        assertThat(capture().headerLines())
                .as("the till is price-list-blind and can quote a customer price or promotion; "
                        + "the report must not be read as a promise of what will be charged")
                .anyMatch(line -> line.contains("Retail") && line.contains("the till may"));
    }

    @Test
    void headerDisclosesWhatTheTotalsLeaveOutAndWhatTheyPullIn() {
        ProductStockTotalsDto t = new ProductStockTotalsDto(
                new BigDecimal("12"), new BigDecimal("6000.00"), new BigDecimal("9200.00"),
                new BigDecimal("3200.00"), 2, 3, 1);
        stubReport(true, report(t, pricedRow()));

        controller.exportStockValue(ExportFormat.PDF, null, null);

        List<String> lines = capture().headerLines();
        assertThat(lines).anyMatch(l -> l.contains("Excluded from totals: 2 item(s) with no cost, 3"));
        assertThat(lines)
                .as("a discontinued item still holding stock belongs in a valuation, but the reader "
                        + "comparing this against the Product List must be told why it is here")
                .anyMatch(l -> l.contains("Includes 1 discontinued item(s) that still hold stock"));
    }

    @Test
    void headerNamesTheVatStance_soTheColumnCannotBeMisread() {
        stubReport(false, report(null, true, pricedRow()));

        controller.exportProductList(ExportFormat.PDF, null, null);

        assertThat(capture().columns().get(5).header()).isEqualTo("Selling Price (VAT incl.)");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void stubReport(boolean withTotals, ProductStockReportDto dto) {
        when(query.report(anyLong(), any(), any(), eq(withTotals))).thenReturn(dto);
    }

    private TabularRenderModel capture() {
        verify(exporter).export(captor.capture(), any(ExportFormat.class));
        return captor.getValue();
    }

    private static ProductStockReportDto report(ProductStockTotalsDto totals,
                                                 ProductStockRowDto... rows) {
        return report(totals, false, rows);
    }

    private static ProductStockReportDto report(ProductStockTotalsDto totals, boolean vatInclusive,
                                                 ProductStockRowDto... rows) {
        ReportCompanyHeaderDto company = new ReportCompanyHeaderDto(
                "Tembo Traders", null, "Plot 5", null, "Arusha", null, "Tanzania",
                null, null, null, null);
        return new ProductStockReportDto(company, null, null, "TZS", "Retail", vatInclusive,
                List.of(rows), totals, "2026-08-11T09:00:00Z");
    }

    private static ProductStockRowDto pricedRow() {
        return new ProductStockRowDto("SUG-001", "Sugar 1kg", "Kilimanjaro Wholesalers",
                new BigDecimal("10"), new BigDecimal("500.0000"), new BigDecimal("5000.00"),
                new BigDecimal("800.00"), new BigDecimal("8000.00"), false);
    }

    /** Sold by weight — {@code stock_on_hand.quantity} is NUMERIC(19,6) and this is ordinary data. */
    private static ProductStockRowDto weighedRow(String quantity) {
        BigDecimal qty = new BigDecimal(quantity);
        BigDecimal unitCost = new BigDecimal("1000.000000");
        return new ProductStockRowDto("SUG-BULK", "Sugar (loose, per kg)", "Kilimanjaro Wholesalers",
                qty, unitCost, qty.multiply(unitCost), new BigDecimal("1400.00"),
                qty.multiply(new BigDecimal("1400.00")), false);
    }

    /** Holds stock, but nobody has ever costed or priced it — the bulk-import shape. */
    private static ProductStockRowDto blankRow() {
        return new ProductStockRowDto("RIC-002", "Rice 5kg", null,
                new BigDecimal("4"), null, null, null, null, false);
    }

    private static ProductStockTotalsDto totals(String cost, String sale) {
        BigDecimal c = new BigDecimal(cost);
        BigDecimal s = new BigDecimal(sale);
        return new ProductStockTotalsDto(new BigDecimal("10"), c, s, s.subtract(c), 0, 0, 0);
    }
}
