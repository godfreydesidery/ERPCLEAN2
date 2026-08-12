package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.stock.domain.dto.StockValuationReconDto.Status;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GL reconciliation behaviour of the stock valuation report.
 *
 * <p>Two live-UAT defects are pinned here:
 * <ol>
 *   <li>a sub-cent moving-average residue flipped the {@code ties} verdict while the page printed
 *       "Difference 0.00" — the report contradicted itself;</li>
 *   <li>an unconfigured GL Inventory account was swallowed and reported as {@code glBalance = 0},
 *       so the alarm claimed the GL was out by the entire value of the stock rather than saying the
 *       check could not be run.</li>
 * </ol>
 *
 * <p>{@link JdbcTemplate} is mocked with a dispatching default answer rather than per-call stubs:
 * the query issues three reads through the same overload, and routing on the SQL keeps the setup
 * readable.
 */
class StockValuationQueryTest {

    private static final Long COMPANY = 5L;
    private static final Long INVENTORY_ACCOUNT = 77L;

    /** Rows returned for the stock_on_hand aggregate: [id, uid, code, name, qty, value, avgCost]. */
    private final List<Object[]> stockRows = new ArrayList<>();
    /** Rows returned for the gl_configs lookup: [accountId, isActive]. */
    private final List<Object[]> glMappingRows = new ArrayList<>();

    private JdbcTemplate          jdbc;
    private JournalLineRepository journalLines;
    private ScopeGuard            scopeGuard;
    private StockValuationQuery   query;

    @BeforeEach
    void setUp() {
        jdbc         = mock(JdbcTemplate.class, withSettings().defaultAnswer(this::dispatch));
        journalLines = mock(JournalLineRepository.class);
        scopeGuard   = mock(ScopeGuard.class);
        query        = new StockValuationQuery(jdbc, journalLines, scopeGuard);

        RequestContext.set(new RequestContext.Principal(1L, "storekeeper", false, COMPANY, 9L, null));

        // Default: the Inventory account is mapped and active.
        glMappingRows.add(new Object[]{INVENTORY_ACCOUNT, Boolean.TRUE});
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        stockRows.clear();
        glMappingRows.clear();
    }

    // -------------------------------------------------------------------------
    // Defect 1 — the report contradicted itself
    // -------------------------------------------------------------------------

    @Test
    void aSubCentResidueNoLongerFlipsTheVerdictAgainstThePrintedFigures() {
        stockOnHand("1186500.0045");
        when(journalLines.accountBalance(COMPANY, INVENTORY_ACCOUNT))
                .thenReturn(new BigDecimal("1186500.0000"));

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.recon().ties()).isTrue();
        assertThat(report.recon().status()).isEqualTo(Status.TIED);
        assertThat(report.recon().difference()).isEqualByComparingTo("0.00");
        // …and the residue is still on the record, not swept away.
        assertThat(report.recon().exactDifference()).isEqualByComparingTo("0.0045");
    }

    @Test
    void aRealGapIsStillReportedAsOutOfBalance() {
        stockOnHand("1186500.0045");
        when(journalLines.accountBalance(COMPANY, INVENTORY_ACCOUNT))
                .thenReturn(new BigDecimal("1186000.0000"));

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.recon().ties()).isFalse();
        assertThat(report.recon().status()).isEqualTo(Status.OUT_OF_BALANCE);
        assertThat(report.recon().difference()).isEqualByComparingTo("500.00");
    }

    /**
     * An account with no journal lines at all reads back null. That is a real zero balance, not an
     * unknown one — stock carrying value against it is a genuine break and must still alarm.
     */
    @Test
    void anInventoryAccountWithNoPostingsIsARealZero_notAnUncheckedState() {
        stockOnHand("250.00");
        when(journalLines.accountBalance(COMPANY, INVENTORY_ACCOUNT)).thenReturn(null);

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.recon().status()).isEqualTo(Status.OUT_OF_BALANCE);
        assertThat(report.recon().expected()).isEqualByComparingTo("0");
        assertThat(report.recon().difference()).isEqualByComparingTo("250.00");
    }

    // -------------------------------------------------------------------------
    // Defect 2 — "not configured" reported as a zero GL balance
    // -------------------------------------------------------------------------

    @Test
    void anUnmappedInventoryAccountIsReportedAsUnchecked_notAsAGapTheSizeOfTheStock() {
        stockOnHand("1186500.0045");
        glMappingRows.clear();          // nothing mapped for the INVENTORY posting role

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.recon().status()).isEqualTo(Status.GL_ACCOUNT_NOT_CONFIGURED);
        assertThat(report.recon().expected()).isNull();
        assertThat(report.recon().difference()).isNull();
        assertThat(report.recon().message()).contains("General Ledger settings");
        // No balance was invented, so none was read.
        verify(journalLines, never()).accountBalance(anyLong(), anyLong());
    }

    @Test
    void anInactiveInventoryAccountIsAlsoReportedAsUnchecked() {
        stockOnHand("1186500.0045");
        glMappingRows.set(0, new Object[]{INVENTORY_ACCOUNT, Boolean.FALSE});

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.recon().status()).isEqualTo(Status.GL_ACCOUNT_NOT_CONFIGURED);
        assertThat(report.recon().expected()).isNull();
        verify(journalLines, never()).accountBalance(anyLong(), anyLong());
    }

    @Test
    void aMappingPointingAtAnAccountThatNoLongerExistsIsReportedAsUnchecked() {
        stockOnHand("1186500.0045");
        glMappingRows.set(0, new Object[]{null, Boolean.FALSE});   // LEFT JOIN found no account row

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.recon().status()).isEqualTo(Status.GL_ACCOUNT_NOT_CONFIGURED);
        verify(journalLines, never()).accountBalance(anyLong(), anyLong());
    }

    /** The stock figures are still the point of the report when the recon cannot run. */
    @Test
    void theValuationItselfIsStillReportedWhenTheGlAccountIsMissing() {
        stockOnHand("1186500.0045");
        glMappingRows.clear();

        StockValuationReportDto report = query.report(COMPANY);

        assertThat(report.rows()).hasSize(1);
        assertThat(report.totalValue()).isEqualByComparingTo("1186500.0045");
        assertThat(report.recon().computed()).isEqualByComparingTo("1186500.0045");
    }

    // -------------------------------------------------------------------------

    /** Tenancy: the report is a cross-branch company read, so the company scope guard must run. */
    @Test
    void theCompanyScopeGuardRunsBeforeAnythingIsRead() {
        query.report(COMPANY);

        verify(scopeGuard).assertCanActIn(any(RequestContext.Principal.class), any(Long.class));
    }

    // -------------------------------------------------------------------------

    private void stockOnHand(String value) {
        stockRows.add(new Object[]{
                10L, "01J0PRODUID", "SKU-1", "Sugar 1kg",
                new BigDecimal("3"), new BigDecimal(value), new BigDecimal("395500.0015")});
    }

    /** Routes each JdbcTemplate.query call to its canned result by the SQL it carries. */
    private Object dispatch(InvocationOnMock invocation) throws Throwable {
        if ("query".equals(invocation.getMethod().getName())
                && invocation.getArguments().length > 0
                && invocation.getArgument(0) instanceof String sql) {
            if (sql.contains("stock_on_hand")) {
                return stockRows;
            }
            if (sql.contains("gl_configs")) {
                return glMappingRows;
            }
            if (sql.contains("FROM companies")) {
                return List.of();     // no letterhead row — the report prints without one
            }
        }
        return Answers.RETURNS_DEFAULTS.answer(invocation);
    }
}
