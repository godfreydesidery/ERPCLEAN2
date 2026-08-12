package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRowDto;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.NormalBalance;
import com.erp.modules.gl.service.TrialBalanceQuery;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.platform.security.PermissionChecks;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.expression.AccessException;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * The trial-balance export (persona UAT: the Finance Director could put the P&amp;L, balance sheet,
 * cash flow and stock valuation on paper on the same token, but
 * {@code GET /api/v1/gl/trial-balance/export} answered 404 — the one statement in the period-close
 * pack that could not be printed).
 *
 * <p>Three things are locked down here:
 * <ol>
 *   <li><b>the gate</b> — the screen's own {@code GL.VIEW} AND {@code REPORT.EXPORT}. Never
 *       {@code REPORT.EXPORT} alone: a download discloses strictly more than one on-screen page, so
 *       a caller the screen refuses must not be able to fetch the same figures as a file (the
 *       inversion {@code ExportPermissionGateTest} scans for). And never {@code GL.VIEW} alone
 *       either — seeing a report is not permission to take it away.</li>
 *   <li><b>what lands on the page</b> — company letterhead, the period the figures cover, debit and
 *       credit right-aligned, and a totals row carrying BOTH totals. A trial balance without its two
 *       totals is not a trial balance.</li>
 *   <li><b>ASCII</b> — the PDF renderer draws Helvetica with its default (Cp1252) encoding and
 *       silently drops anything outside it. A minus sign lost from an out-of-balance figure is the
 *       worst character in the document to lose.</li>
 * </ol>
 *
 * <p>The exporter is mocked, so this stays a fast surefire test — the renderers themselves are
 * covered by {@code TabularExporterTest}.
 */
class TrialBalanceExportTest {

    private static final Pattern PERM_HAS = Pattern.compile("@perm\\.has\\('([^']+)'\\)");

    /** U+2212 MINUS SIGN, built from its code point so the assertion cannot depend on file encoding. */
    private static final String MINUS_SIGN = String.valueOf((char) 0x2212);

    private static final String VIEW   = "GL.VIEW";
    private static final String EXPORT = "REPORT.EXPORT";

    /** Cell indexes in both the data rows and the totals row. */
    private static final int CODE_CELL   = 0;
    private static final int NAME_CELL   = 1;
    private static final int DEBIT_CELL  = 3;
    private static final int CREDIT_CELL = 4;
    private static final int NET_CELL    = 5;
    private static final int COLUMNS     = 6;

    private TrialBalanceQuery      query;
    private TabularExporter        exporter;
    private TrialBalanceController controller;
    private ArgumentCaptor<TabularRenderModel> captor;

    @BeforeEach
    void setUp() {
        query      = mock(TrialBalanceQuery.class);
        exporter   = mock(TabularExporter.class);
        controller = new TrialBalanceController(query, exporter);
        captor     = ArgumentCaptor.forClass(TabularRenderModel.class);

        when(exporter.export(any(), any()))
                .thenReturn(new ExportResult(new byte[]{1}, "application/pdf", "trial-balance.pdf"));
    }

    // -------------------------------------------------------------------------
    // 1 — the endpoint exists and answers with a downloadable file
    // -------------------------------------------------------------------------

    @Test
    void export_returnsAnAttachmentRatherThan404() {
        when(query.compute(anyLong())).thenReturn(balanced());

        ResponseEntity<byte[]> response = controller.export(1L, null, ExportFormat.PDF);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .as("the browser must be told to save the file, not render the bytes")
                .contains("attachment")
                .contains("trial-balance.pdf");
        assertThat(response.getBody()).isNotEmpty();
    }

    @Test
    void export_withoutAPeriod_printsTheWholeCompanyAllPeriods() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(7L, null, ExportFormat.PDF);

        verify(query).compute(7L);
        verify(query, never()).computeForPeriod(anyLong(), anyLong());
    }

    @Test
    void export_withAPeriod_printsThatPeriodOnly() {
        when(query.computeForPeriod(anyLong(), anyLong())).thenReturn(balanced());

        controller.export(7L, 42L, ExportFormat.XLSX);

        verify(query).computeForPeriod(7L, 42L);
        verify(query, never()).compute(anyLong());
    }

    @Test
    void export_passesTheRequestedFormatStraightThrough() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.CSV);

        verify(exporter).export(any(), eq(ExportFormat.CSV));
    }

    // -------------------------------------------------------------------------
    // 2 — the permission gate
    // -------------------------------------------------------------------------

    @Test
    void exportGate_deniedWithTheExportPermissionAlone() {
        assertThat(evaluateGate(gateOf("export"), Set.of(EXPORT)))
                .as("a caller refused the trial balance on screen must not be able to download it")
                .isFalse();
    }

    @Test
    void exportGate_deniedWithTheViewPermissionAlone() {
        assertThat(evaluateGate(gateOf("export"), Set.of(VIEW)))
                .as("seeing the trial balance is not permission to take it away — %s is still "
                        + "required", EXPORT)
                .isFalse();
    }

    @Test
    void exportGate_allowedWithBothPermissions() {
        assertThat(evaluateGate(gateOf("export"), Set.of(VIEW, EXPORT)))
                .as("the seeded FINANCE_DIRECTOR bundle holds exactly these two codes")
                .isTrue();
    }

    @Test
    void exportGate_requiresEverythingItsViewSiblingRequires() {
        assertThat(codesIn(gateOf("export")))
                .as("the export gate must be a superset of the on-screen gate")
                .containsAll(codesIn(gateOf("get")));
    }

    @Test
    void everyHandler_usesPermHas_notHasAuthority() {
        for (String name : List.of("get", "getForPeriod", "export")) {
            assertThat(gateOf(name))
                    .as("%s must use @perm.has (root-bypass aware), not hasAuthority "
                            + "(JWT-claims only, which denies rootadmin)", name)
                    .contains("@perm.has")
                    .doesNotContain("hasAuthority");
        }
    }

    // -------------------------------------------------------------------------
    // 3 — what actually lands on the page
    // -------------------------------------------------------------------------

    @Test
    void theTotalsRowCarriesBothTotals_alignedUnderTheirColumns() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.PDF);

        TabularRenderModel model = capture();
        assertThat(model.columns()).hasSize(COLUMNS);
        assertThat(model.columns().get(DEBIT_CELL).header()).isEqualTo("Debit");
        assertThat(model.columns().get(CREDIT_CELL).header()).isEqualTo("Credit");
        assertThat(model.totalsRow())
                .as("a trial balance that does not show its two totals is useless")
                .isNotNull()
                .hasSize(COLUMNS);
        assertThat(model.totalsRow().get(DEBIT_CELL)).isEqualTo("1,250,000.00");
        assertThat(model.totalsRow().get(CREDIT_CELL)).isEqualTo("1,250,000.00");
        assertThat(model.totalsRow().get(NAME_CELL)).isEqualTo("TOTAL");
    }

    @Test
    void moneyColumnsAreRightAligned_andLabelColumnsAreNot() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.PDF);

        TabularRenderModel model = capture();
        assertThat(model.columns().get(DEBIT_CELL).align()).isEqualTo(Align.RIGHT);
        assertThat(model.columns().get(CREDIT_CELL).align()).isEqualTo(Align.RIGHT);
        assertThat(model.columns().get(NET_CELL).align()).isEqualTo(Align.RIGHT);
        assertThat(model.columns().get(CODE_CELL).align()).isEqualTo(Align.LEFT);
        assertThat(model.columns().get(NAME_CELL).align()).isEqualTo(Align.LEFT);
    }

    @Test
    void everyRowIsFullWidth_soNoCellDriftsUnderTheWrongHeading() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.PDF);

        assertThat(capture().rows()).isNotEmpty().allSatisfy(r -> assertThat(r).hasSize(COLUMNS));
    }

    @Test
    void theLetterheadCarriesTheCompanyAndThePeriodTheFiguresCover() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.PDF);

        TabularRenderModel model = capture();
        assertThat(model.title()).isEqualTo("Trial Balance");
        assertThat(model.headerLines()).contains("Tembo Traders");
        assertThat(model.headerLines()).anyMatch(l -> l.startsWith("Period: "));
        assertThat(model.headerLines()).anyMatch(l -> l.equals("Currency: TZS"));
        assertThat(model.generatedAt())
                .as("a printed statement with no generated-at cannot be told apart from last "
                        + "month's copy of the same page")
                .isEqualTo("2026-08-11T09:00:00Z");
    }

    /** A missing letterhead row must cost the accountant her letterhead, never her trial balance. */
    @Test
    void aCompanyWithNoLetterheadStillExports() {
        when(query.compute(anyLong())).thenReturn(new TrialBalanceDto(
                1L, null, null, null, List.of(assetRow()),
                new BigDecimal("1250000.00"), new BigDecimal("1250000.00"), null));

        controller.export(1L, null, ExportFormat.PDF);

        TabularRenderModel model = capture();
        assertThat(model.rows()).hasSize(1);
        assertThat(model.generatedAt()).isNotNull();
    }

    @Test
    void aZeroSideIsBlank_butAZeroTotalIsPrinted() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.PDF);

        TabularRenderModel model = capture();
        // 1000 Cash: all debit, no credit — the credit cell is blank, the way a TB is laid out.
        assertThat(model.rows().get(0).get(CREDIT_CELL)).isEmpty();
        assertThat(model.rows().get(0).get(DEBIT_CELL)).isEqualTo("1,250,000.00");
        // A balanced book nets to zero, and "0.00" is the answer there — not an absence.
        assertThat(model.totalsRow().get(NET_CELL)).isEqualTo("0.00");
    }

    // -------------------------------------------------------------------------
    // 4 — balance disclosure + ASCII
    // -------------------------------------------------------------------------

    @Test
    void aBalancedSetOfBooksSaysSoOnThePage() {
        when(query.compute(anyLong())).thenReturn(balanced());

        controller.export(1L, null, ExportFormat.PDF);

        assertThat(capture().headerLines())
                .anyMatch(l -> l.contains("Balanced") && l.contains("equal"));
    }

    @Test
    void anOutOfBalanceSetOfBooksSaysSoOnThePage_withTheAmount() {
        when(query.compute(anyLong())).thenReturn(outOfBalance());

        controller.export(1L, null, ExportFormat.PDF);

        assertThat(capture().headerLines())
                .as("a close pack carrying an unbalanced trial balance that does not say so is a "
                        + "filed error")
                .anyMatch(l -> l.contains("OUT OF BALANCE") && l.contains("50,000.00"));
    }

    /**
     * Everything the PDF puts in its head must survive Helvetica's default (Cp1252) encoding.
     * U+2212 MINUS SIGN is the character that bit us: it is dropped silently, so an out-of-balance
     * figure would print without its sign.
     */
    @Test
    void headerTextIsAsciiOnly_soNoCharacterIsSilentlyDropped() {
        when(query.compute(anyLong())).thenReturn(outOfBalance());

        controller.export(1L, null, ExportFormat.PDF);

        TabularRenderModel model = capture();
        for (String line : model.headerLines()) {
            assertThat(line.chars().allMatch(ch -> ch < 128))
                    .as("non-ASCII in a PDF header line: \"%s\"", line)
                    .isTrue();
        }
        assertThat(model.totalsRow())
                .as("an ASCII '-', never U+2212 (MINUS SIGN), on the figures too")
                .allSatisfy(cell -> assertThat(cell).doesNotContain(MINUS_SIGN));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private TabularRenderModel capture() {
        verify(exporter).export(captor.capture(), any(ExportFormat.class));
        return captor.getValue();
    }

    private static TrialBalanceDto balanced() {
        return new TrialBalanceDto(1L, company(), "TZS", "All periods",
                List.of(assetRow(), incomeRow()),
                new BigDecimal("1250000.00"), new BigDecimal("1250000.00"),
                "2026-08-11T09:00:00Z");
    }

    private static TrialBalanceDto outOfBalance() {
        return new TrialBalanceDto(1L, company(), "TZS", "Period 3: 2026-03-01 to 2026-03-31",
                List.of(assetRow(), incomeRow()),
                new BigDecimal("1250000.00"), new BigDecimal("1300000.00"),
                "2026-08-11T09:00:00Z");
    }

    private static ReportCompanyHeaderDto company() {
        return new ReportCompanyHeaderDto("Tembo Traders", "Tembo Traders Ltd", "Plot 5", null,
                "Arusha", null, "Tanzania", "+255700000000", "books@tembo.co.tz", "123-456-789",
                "40-000000-A");
    }

    private static TrialBalanceRowDto assetRow() {
        return new TrialBalanceRowDto(10L, "01J0ACCOUNT0000000000CASH", "1000", "Cash",
                AccountType.ASSET, NormalBalance.DEBIT,
                new BigDecimal("1250000.00"), BigDecimal.ZERO, new BigDecimal("1250000.00"));
    }

    private static TrialBalanceRowDto incomeRow() {
        return new TrialBalanceRowDto(20L, "01J0ACCOUNT0000000SALES", "4100", "Sales Revenue",
                AccountType.INCOME, NormalBalance.CREDIT,
                BigDecimal.ZERO, new BigDecimal("1250000.00"), new BigDecimal("-1250000.00"));
    }

    /** The live {@code @PreAuthorize} expression on the named handler. */
    private static String gateOf(String methodName) {
        Method handler = null;
        for (Method m : TrialBalanceController.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                handler = m;
            }
        }
        assertThat(handler).as("TrialBalanceController#%s must exist", methodName).isNotNull();
        PreAuthorize gate = handler.getAnnotation(PreAuthorize.class);
        assertThat(gate).as("%s must carry @PreAuthorize", methodName).isNotNull();
        return gate.value();
    }

    /**
     * Evaluates a {@code @PreAuthorize} expression against a principal holding exactly
     * {@code heldCodes}. Same harness as {@code ExportPermissionGateTest} — no Spring context, so
     * this stays a fast surefire test.
     */
    private static boolean evaluateGate(String expression, Set<String> heldCodes) {
        PermissionChecks perm = mock(PermissionChecks.class);
        for (String code : codesIn(expression)) {
            when(perm.has(code)).thenReturn(heldCodes.contains(code));
        }
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanResolver() {
            @Override
            public Object resolve(EvaluationContext ctx, String beanName) throws AccessException {
                if ("perm".equals(beanName)) {
                    return perm;
                }
                throw new AccessException("Unexpected bean in a permission expression: " + beanName);
            }
        });
        return Boolean.TRUE.equals(
                new SpelExpressionParser().parseExpression(expression).getValue(context, Boolean.class));
    }

    private static Set<String> codesIn(String expression) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = PERM_HAS.matcher(expression);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }
}
