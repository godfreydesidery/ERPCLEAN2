package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.ApAgeingRowDto;
import com.erp.modules.ap.domain.dto.ApSupplierLedgerDto;
import com.erp.modules.ap.domain.dto.ApSupplierLedgerRowDto;
import com.erp.modules.ap.domain.dto.ApSupplierRefDto;
import com.erp.modules.ap.domain.enums.ApLedgerEntryType;
import com.erp.modules.ar.domain.dto.ArCustomerAgeingRowDto;
import com.erp.modules.ar.domain.dto.ArCustomerLedgerDto;
import com.erp.modules.ar.domain.dto.ArCustomerLedgerRowDto;
import com.erp.modules.ar.domain.enums.AgeingBucket;
import com.erp.modules.ar.domain.enums.ArLedgerEntryType;
import com.erp.modules.cashbank.domain.dto.CashAccountStatementDto;
import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.tax.domain.dto.VatReturnBandDto;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtRegisterRowDto;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.platform.security.RequestContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * What the sub-ledger exports put on the page: the letterhead, balance brought forward, the
 * running balance, debit/credit sides, totals and the foot. The flatteners are pure functions of
 * the read's DTO, so these run without Spring or a database (the native SQL behind the AR/AP
 * statements is covered by {@code ArCustomerLedgerQueryIT} / {@code ApSupplierLedgerQueryIT}).
 */
class LedgerExportFlattenTest {

    private static final ZonedDateTime NOW =
            ZonedDateTime.of(2026, 10, 2, 10, 15, 0, 0, ZoneId.of("Africa/Dar_es_Salaam"));

    private static final ReportCompanyHeaderDto COMPANY = new ReportCompanyHeaderDto(
            "Kilimanjaro Traders", null, "Plot 7", null, "Moshi", null, "Tanzania",
            "+255 700 000 000", null, "100-200-300", "40-012345-A");

    private static final ExportLetterhead.Letterhead HEAD =
            new ExportLetterhead.Letterhead(COMPANY, null);

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Principal(1L, "amwanga", false, 5L, 9L, null, 3L));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Customer statement
    // -------------------------------------------------------------------------

    @Test
    void customerStatement_carriesLetterheadBroughtForwardRunningBalanceAndClosing() {
        ArCustomerLedgerDto dto = new ArCustomerLedgerDto(COMPANY, "CUID", "C001", "Duka la Mama",
                "111-222-333", null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "TZS",
                bd("1000"),
                List.of(
                        new ArCustomerLedgerRowDto(LocalDate.of(2026, 9, 3), ArLedgerEntryType.INVOICE,
                                "INV-1", "Invoice, due 03-Oct-2026", bd("500"), BigDecimal.ZERO, bd("1500")),
                        new ArCustomerLedgerRowDto(LocalDate.of(2026, 9, 9), ArLedgerEntryType.RECEIPT,
                                "RC-1", "Payment received (CASH)", BigDecimal.ZERO, bd("1200"), bd("300"))),
                bd("500"), bd("1200"), bd("300"), 2, "2026-10-02T07:15:00Z");

        TabularRenderModel m = ArStatementController.flattenStatement(dto, HEAD, NOW);

        assertThat(m.title()).isEqualTo("Customer Statement");
        assertThat(m.headerLines()).contains("Kilimanjaro Traders", "Plot 7, Moshi, Tanzania",
                "TIN: 100-200-300", "VRN: 40-012345-A", "Customer: C001 — Duka la Mama",
                "Customer TIN: 111-222-333", "Period: 2026-09-01 to 2026-09-30", "Currency: TZS");
        assertThat(m.columns()).hasSize(7);
        // Balance b/f is the first row and sits in the Balance column only.
        assertThat(m.rows().get(0)).containsExactly(
                "2026-09-01", "", "", "Balance brought forward", "", "", "1,000.00");
        // An invoice prints in Debit only; a receipt in Credit only — never a "0.00" on the other side.
        assertThat(m.rows().get(1)).containsExactly(
                "2026-09-03", "Invoice", "INV-1", "Invoice, due 03-Oct-2026", "500.00", "", "1,500.00");
        assertThat(m.rows().get(2)).containsExactly(
                "2026-09-09", "Receipt", "RC-1", "Payment received (CASH)", "", "1,200.00", "300.00");
        assertThat(m.totalsRow()).containsExactly(
                "", "", "", "Closing balance", "500.00", "1,200.00", "300.00");
        assertThat(m.footerLines()).contains("Amount due from customer: TZS 300.00");
        assertThat(m.footerLines()).anyMatch(l -> l.startsWith("Note: 2 transactions are in another currency"));
        assertThat(m.footerLines()).last().asString()
                .contains("Printed By: amwanga").contains("Printed From: Kilimanjaro Traders");
    }

    @Test
    void customerStatement_withoutFromDate_hasNoBroughtForwardRow_andSaysCustomerInCredit() {
        ArCustomerLedgerDto dto = new ArCustomerLedgerDto(COMPANY, "CUID", "C001", "Duka la Mama",
                null, null, null, LocalDate.of(2026, 9, 30), "TZS", BigDecimal.ZERO,
                List.of(new ArCustomerLedgerRowDto(LocalDate.of(2026, 9, 9), ArLedgerEntryType.RECEIPT,
                        "RC-1", "Payment received (CASH)", BigDecimal.ZERO, bd("200"), bd("-200"))),
                BigDecimal.ZERO, bd("200"), bd("-200"), 0, "x");

        TabularRenderModel m = ArStatementController.flattenStatement(dto, HEAD, NOW);

        assertThat(m.headerLines()).contains("Period: all transactions up to 2026-09-30");
        assertThat(m.rows()).hasSize(1);
        assertThat(m.rows().get(0).get(6)).isEqualTo("-200.00");
        assertThat(m.footerLines()).contains("Customer in credit: TZS 200.00");
        assertThat(m.footerLines()).noneMatch(l -> l.startsWith("Note:"));
    }

    @Test
    void customerStatement_emptyPeriod_saysSo() {
        ArCustomerLedgerDto dto = new ArCustomerLedgerDto(COMPANY, "CUID", "C001", "Duka", null, null,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "TZS", BigDecimal.ZERO, List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, "x");

        TabularRenderModel m = ArStatementController.flattenStatement(dto, HEAD, NOW);

        assertThat(m.footerLines()).contains("No transactions in this period.",
                "Nothing outstanding at the end of the period.");
    }

    @Test
    void customerStatement_twoCurrencies_printOneSectionEach_withNoGrandTotal() {
        ArCustomerLedgerDto usd = new ArCustomerLedgerDto(COMPANY, "CUID", "C009", "Dollar Lodge",
                null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "USD", bd("100"),
                List.of(new ArCustomerLedgerRowDto(LocalDate.of(2026, 9, 4), ArLedgerEntryType.INVOICE,
                        "INV-9", "Invoice, due 04-Oct-2026", bd("1200"), BigDecimal.ZERO, bd("1300"))),
                bd("1200"), BigDecimal.ZERO, bd("1300"), 1, "x");
        ArCustomerLedgerDto tzs = new ArCustomerLedgerDto(COMPANY, "CUID", "C009", "Dollar Lodge",
                null, null, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "TZS", BigDecimal.ZERO,
                List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, "x");

        TabularRenderModel m = ArStatementController.flattenStatement(List.of(usd, tzs), HEAD, NOW);

        assertThat(m.headerLines()).noneMatch(l -> l.startsWith("Currency:"));
        assertThat(m.headerLines()).anyMatch(l -> l.startsWith("Currencies: USD, TZS"));
        assertThat(m.totalsRow()).as("no total across currencies").isNull();
        assertThat(m.rows()).containsExactly(
                List.of("", "", "", "Currency: USD", "", "", ""),
                List.of("2026-09-01", "", "", "Balance brought forward", "", "", "100.00"),
                List.of("2026-09-04", "Invoice", "INV-9", "Invoice, due 04-Oct-2026", "1,200.00", "",
                        "1,300.00"),
                List.of("", "", "", "Closing balance USD", "1,200.00", "0.00", "1,300.00"),
                List.of("", "", "", "Currency: TZS", "", "", ""),
                List.of("2026-09-01", "", "", "Balance brought forward", "", "", "0.00"),
                List.of("", "", "", "Closing balance TZS", "0.00", "0.00", "0.00"));
        assertThat(m.footerLines()).contains("Amount due from customer: USD 1,300.00",
                "Nothing outstanding at the end of the period.");
        // The other currency is IN this document, so no "not included" note.
        assertThat(m.footerLines()).noneMatch(l -> l.startsWith("Note:"));
        assertThat(m.footerLines()).doesNotContain("No transactions in this period.");
    }

    // -------------------------------------------------------------------------
    // AR ageing by customer
    // -------------------------------------------------------------------------

    @Test
    void arAgeing_oneRowPerCustomer_andTotalsLineUpWithTheBucketColumns() {
        List<ArCustomerAgeingRowDto> rows = List.of(
                new ArCustomerAgeingRowDto(1L, "C001", "Duka A", bd("100"), bd("50"), BigDecimal.ZERO,
                        BigDecimal.ZERO, bd("10"), bd("160"), "TZS"),
                new ArCustomerAgeingRowDto(2L, "C002", "Duka B", bd("0"), bd("20"), bd("30"),
                        bd("40"), BigDecimal.ZERO, bd("90"), "TZS"));

        TabularRenderModel m = ArStatementController.flattenAgeing(rows, LocalDate.of(2026, 9, 30), HEAD, NOW);

        assertThat(m.title()).isEqualTo("AR Ageing by Customer");
        assertThat(m.headerLines()).contains("Ageing as at 2026-09-30", "Currency: TZS");
        assertThat(m.columns()).hasSize(8);
        assertThat(m.rows().get(0)).containsExactly(
                "C001", "Duka A", "100.00", "50.00", "0.00", "0.00", "10.00", "160.00");
        assertThat(m.totalsRow()).containsExactly(
                "", "TOTAL (2 customers)", "100.00", "70.00", "30.00", "40.00", "10.00", "250.00");
    }

    @Test
    void arAgeing_twoCurrencies_getACurrencyColumn_andATotalPerCurrency_neverOneMixedSum() {
        List<ArCustomerAgeingRowDto> rows = List.of(
                new ArCustomerAgeingRowDto(1L, "C001", "Duka A", bd("1000"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, bd("1000"), "TZS"),
                new ArCustomerAgeingRowDto(1L, "C001", "Duka A", BigDecimal.ZERO, BigDecimal.ZERO,
                        bd("500"), BigDecimal.ZERO, BigDecimal.ZERO, bd("500"), "USD"),
                new ArCustomerAgeingRowDto(2L, "C002", "Duka B", bd("200"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, bd("200"), "TZS"));

        TabularRenderModel m = ArStatementController.flattenAgeing(rows, LocalDate.of(2026, 9, 30), HEAD, NOW);

        assertThat(m.headerLines()).noneMatch(l -> l.startsWith("Currency:"));
        assertThat(m.columns()).hasSize(9);
        assertThat(m.columns().get(2).header()).isEqualTo("Currency");
        assertThat(m.rows().get(1)).containsExactly(
                "C001", "Duka A", "USD", "0.00", "0.00", "500.00", "0.00", "0.00", "500.00");
        assertThat(m.totalsRow()).as("no grand total across currencies").isNull();
        assertThat(m.rows()).hasSize(5);
        assertThat(m.rows().get(3)).containsExactly(
                "", "TOTAL TZS (2 customers)", "TZS", "1,200.00", "0.00", "0.00", "0.00", "0.00", "1,200.00");
        assertThat(m.rows().get(4)).containsExactly(
                "", "TOTAL USD (1 customer)", "USD", "0.00", "0.00", "500.00", "0.00", "0.00", "500.00");
    }

    // -------------------------------------------------------------------------
    // Supplier statement + ageing
    // -------------------------------------------------------------------------

    @Test
    void supplierStatement_billsAreCreditsPaymentsAreDebits_closingIsWhatWeOwe() {
        ApSupplierLedgerDto dto = new ApSupplierLedgerDto(COMPANY, "SUID", "S001", "Mbasha Holdings",
                null, "40-999", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "TZS",
                BigDecimal.ZERO,
                List.of(
                        new ApSupplierLedgerRowDto(LocalDate.of(2026, 9, 2), ApLedgerEntryType.BILL,
                                "BILL-1", "Bill, supplier invoice SI-9", BigDecimal.ZERO, bd("1000"), bd("1000")),
                        new ApSupplierLedgerRowDto(LocalDate.of(2026, 9, 20), ApLedgerEntryType.PAYMENT,
                                "PAY-1", "Payment (BANK TRANSFER)", bd("400"), BigDecimal.ZERO, bd("600"))),
                bd("400"), bd("1000"), bd("600"), 0, "x");

        TabularRenderModel m = ApStatementController.flattenStatement(dto, HEAD, NOW);

        assertThat(m.title()).isEqualTo("Supplier Statement");
        assertThat(m.headerLines()).contains("Supplier: S001 — Mbasha Holdings", "Supplier VRN: 40-999");
        assertThat(m.rows().get(1)).containsExactly(
                "2026-09-02", "Bill", "BILL-1", "Bill, supplier invoice SI-9", "", "1,000.00", "1,000.00");
        assertThat(m.rows().get(2)).containsExactly(
                "2026-09-20", "Payment", "PAY-1", "Payment (BANK TRANSFER)", "400.00", "", "600.00");
        assertThat(m.footerLines()).contains("Amount owed to supplier: TZS 600.00");
    }

    @Test
    void supplierAgeing_labelsTheBucketsAndTotalsThem() {
        List<ApAgeingRowDto> ageing = List.of(
                new ApAgeingRowDto(AgeingBucket.CURRENT, bd("100"), "TZS"),
                new ApAgeingRowDto(AgeingBucket.D1_30, bd("50"), "TZS"),
                new ApAgeingRowDto(AgeingBucket.D31_60, BigDecimal.ZERO, "TZS"),
                new ApAgeingRowDto(AgeingBucket.D61_90, BigDecimal.ZERO, "TZS"),
                new ApAgeingRowDto(AgeingBucket.D90_PLUS, bd("25"), "TZS"));
        ApSupplierRefDto supplier = new ApSupplierRefDto(7L, "SUID", "S001", "Mbasha", null, null);

        TabularRenderModel m = ApStatementController.flattenAgeing(
                ageing, supplier, LocalDate.of(2026, 9, 30), HEAD, NOW);

        assertThat(m.rows()).extracting(r -> r.get(0)).containsExactly(
                "Current (not yet due)", "1-30 days", "31-60 days", "61-90 days", "Over 90 days");
        assertThat(m.totalsRow()).containsExactly("TOTAL OUTSTANDING", "175.00");
        assertThat(m.headerLines()).contains("Supplier: S001 — Mbasha", "Ageing as at 2026-09-30");
    }

    @Test
    void supplierAgeing_twoCurrencies_areTotalledSeparately() {
        List<ApAgeingRowDto> ageing = new java.util.ArrayList<>();
        for (AgeingBucket b : AgeingBucket.values()) {
            ageing.add(new ApAgeingRowDto(b, b == AgeingBucket.CURRENT ? bd("1000") : BigDecimal.ZERO, "TZS"));
        }
        for (AgeingBucket b : AgeingBucket.values()) {
            ageing.add(new ApAgeingRowDto(b, b == AgeingBucket.D31_60 ? bd("400") : BigDecimal.ZERO, "USD"));
        }
        ApSupplierRefDto supplier = new ApSupplierRefDto(7L, "SUID", "S001", "Mbasha", null, null);

        TabularRenderModel m = ApStatementController.flattenAgeing(
                ageing, supplier, LocalDate.of(2026, 9, 30), HEAD, NOW);

        assertThat(m.columns()).extracting(TabularRenderModel.Column::header)
                .containsExactly("Age (days past due)", "Currency", "Amount");
        assertThat(m.rows().get(7)).containsExactly("31-60 days", "USD", "400.00");
        assertThat(m.totalsRow()).isNull();
        assertThat(m.rows().get(10)).containsExactly("TOTAL OUTSTANDING TZS", "TZS", "1,000.00");
        assertThat(m.rows().get(11)).containsExactly("TOTAL OUTSTANDING USD", "USD", "400.00");
    }

    // -------------------------------------------------------------------------
    // Cash account statement — the period is cut in the flattener
    // -------------------------------------------------------------------------

    @Test
    void cashStatement_foldsEarlierLinesIntoBroughtForward_andDropsLinesAfterTheEndDate() {
        CashAccountStatementDto stmt = new CashAccountStatementDto(3L, "ACCUID", "CRDB Main",
                bd("1150"), "TZS", List.of(
                        txn(1L, LocalDate.of(2026, 8, 30), CashTxnDirection.IN, "1000", CashTxnType.AR_RECEIPT),
                        txn(2L, LocalDate.of(2026, 9, 5), CashTxnDirection.OUT, "300", CashTxnType.AP_PAYMENT),
                        txn(3L, LocalDate.of(2026, 9, 10), CashTxnDirection.IN, "200", CashTxnType.DIRECT_ENTRY),
                        txn(4L, LocalDate.of(2026, 10, 1), CashTxnDirection.IN, "250", CashTxnType.TRANSFER_IN)));
        CashBankAccountDto account = account();

        TabularRenderModel m = CashAccountStatementController.flattenStatement(stmt, account,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), HEAD, NOW);

        assertThat(m.headerLines()).contains("Account: BANK1 — CRDB Main",
                "Bank: CRDB, Moshi    Account No: 0150-123", "Period: 2026-09-01 to 2026-09-30");
        assertThat(m.rows()).hasSize(3); // b/f + two September lines; the October line is left off
        assertThat(m.rows().get(0)).containsExactly(
                "2026-09-01", "", "", "", "Balance brought forward", "", "", "1,000.00");
        assertThat(m.rows().get(1)).containsExactly(
                "2026-09-05", "T2", "Supplier payment", "REF2", "memo 2", "", "300.00", "700.00");
        assertThat(m.rows().get(2)).containsExactly(
                "2026-09-10", "T3", "Direct entry", "REF3", "memo 3", "200.00", "", "900.00");
        assertThat(m.totalsRow()).containsExactly(
                "", "", "", "", "Closing balance", "200.00", "300.00", "900.00");
        // A past period's closing is not today's balance — the foot gives today's separately.
        assertThat(m.footerLines()).contains("Current book balance today: TZS 1,150.00");
    }

    @Test
    void cashStatement_withoutDates_isTheWholeHistoryFromZero() {
        CashAccountStatementDto stmt = new CashAccountStatementDto(3L, "ACCUID", "Till", bd("700"), "TZS",
                List.of(txn(1L, LocalDate.of(2026, 8, 30), CashTxnDirection.IN, "1000", CashTxnType.AR_RECEIPT),
                        txn(2L, LocalDate.of(2026, 9, 5), CashTxnDirection.OUT, "300", CashTxnType.AP_PAYMENT)));

        TabularRenderModel m = CashAccountStatementController.flattenStatement(
                stmt, null, null, null, HEAD, NOW);

        assertThat(m.rows()).hasSize(2);
        assertThat(m.rows().get(1).get(7)).isEqualTo("700.00");
        assertThat(m.headerLines()).contains("Period: all transactions up to 2026-09-05");
        assertThat(m.footerLines()).noneMatch(l -> l.startsWith("Current book balance"));
    }

    // -------------------------------------------------------------------------
    // VAT return face
    // -------------------------------------------------------------------------

    @Test
    void vatReturn_printsTheFaceAsTheScreenDoes_andMarksADraft() {
        VatReturnDto r = vatReturn(VatReturnStatus.DRAFT, bd("1800"), null);

        TabularRenderModel m = VatReturnController.flatten(r, HEAD, NOW);

        assertThat(m.title()).isEqualTo("VAT Return VR-2026-09");
        assertThat(m.headerLines()).contains("TIN: 100-200-300", "VRN: 40-012345-A",
                "Return No: VR-2026-09", "Status: DRAFT — not yet filed; figures may still change");
        assertThat(m.rows()).contains(
                List.of("  Standard rated", "10,000.00", "1,800.00"),
                List.of("  Zero rated", "500.00", "0.00"),
                List.of("Total sales turnover / output VAT", "10,500.00", "1,800.00"),
                List.of("Less: input VAT (deductible)", "", "(600.00)"),
                // purchases turnover not computed: blank, never 0.00
                List.of("Purchases turnover", "", ""));
        assertThat(m.totalsRow()).containsExactly("Net VAT — Payable to TRA", "", "1,200.00");
    }

    @Test
    void vatReturn_filed_showsTraReference_andACreditReadsAsCarriedForward() {
        VatReturnDto r = vatReturn(VatReturnStatus.FILED, bd("1800"), bd("-300"));

        TabularRenderModel m = VatReturnController.flatten(r, HEAD, NOW);

        assertThat(m.headerLines()).contains(
                "Status: FILED on 2026-10-15    TRA reference: TRA/VAT/1");
        assertThat(m.totalsRow().get(0)).isEqualTo("Net VAT — Credit carried forward");
        assertThat(m.footerLines()).contains("Credit carried forward to next period: 300.00");
    }

    // -------------------------------------------------------------------------
    // WHT register
    // -------------------------------------------------------------------------

    @Test
    void whtRegister_twoSectionsWithSubtotals_andNoMeaninglessGrandTotal() {
        WhtRegisterDto dto = new WhtRegisterDto(5L, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
                List.of(new WhtRegisterRowDto("WHT-1", WhtKind.WHT_ON_PAYMENT, "SUPPLIER", "Mbasha",
                        "PAY-1", bd("1000"), bd("50"), LocalDate.of(2026, 9, 3))),
                bd("50"), List.of(), BigDecimal.ZERO);

        TabularRenderModel m = WhtRegisterController.flatten(dto, HEAD, NOW);

        assertThat(m.totalsRow()).isNull();
        assertThat(m.rows()).contains(
                List.of("WHT-1", "2026-09-03", "Mbasha", "PAY-1", "1,000.00", "50.00"),
                List.of("", "", "Total payable (1)", "", "1,000.00", "50.00"),
                List.of("No certificates in this period", "", "", "", "", ""),
                List.of("", "", "Total receivable (0)", "", "0.00", "0.00"));
        assertThat(m.headerLines()).contains("Period: 2026-09-01 to 2026-09-30");
    }

    // -------------------------------------------------------------------------
    // Gates — each export requires its screen's code AND REPORT.EXPORT
    // -------------------------------------------------------------------------

    @Test
    void everyNewExportRequiresItsScreenGateAndReportExport() throws Exception {
        assertGate(ArStatementController.class, "exportStatement",
                "@perm.has('AR.STATEMENT.VIEW') and @perm.has('REPORT.EXPORT')");
        assertGate(ArStatementController.class, "exportAgeingByCustomer",
                "@perm.has('AR.STATEMENT.VIEW') and @perm.has('REPORT.EXPORT')");
        assertGate(ApStatementController.class, "exportStatement",
                "@perm.has('AP.VIEW') and @perm.has('REPORT.EXPORT')");
        assertGate(ApStatementController.class, "exportAgeing",
                "@perm.has('AP.VIEW') and @perm.has('REPORT.EXPORT')");
        assertGate(CashAccountStatementController.class, "exportStatement",
                "@perm.scoped(#uid,'cashbankaccount','CASH.VIEW') and @perm.has('REPORT.EXPORT')");
        assertGate(VatReturnController.class, "export",
                "@perm.scoped(#uid,'vatreturn','VAT.VIEW') and @perm.has('REPORT.EXPORT')");
        assertGate(WhtRegisterController.class, "exportRegister",
                "@perm.has('WHT.VIEW') and @perm.has('REPORT.EXPORT')");
    }

    // -------------------------------------------------------------------------

    private static void assertGate(Class<?> controller, String method, String expected) {
        Method found = null;
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getName().equals(method) && m.isAnnotationPresent(PreAuthorize.class)) {
                found = m;
            }
        }
        assertThat(found).as("%s#%s must exist and be gated", controller.getSimpleName(), method)
                .isNotNull();
        assertThat(found.getAnnotation(PreAuthorize.class).value()).isEqualTo(expected);
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    private static CashTransactionDto txn(Long id, LocalDate date, CashTxnDirection dir, String amount,
                                          CashTxnType type) {
        return new CashTransactionDto(id, "U" + id, 5L, 3L, "T" + id, date, null, null, dir,
                bd(amount), "TZS", type, "REF" + id, null, null, false, null, "memo " + id);
    }

    private static CashBankAccountDto account() {
        return new CashBankAccountDto(3L, "ACCUID", 5L, 9L, "BANK1", "CRDB Main",
                CashBankAccountType.values()[0], "CRDB", "0150-123", "Moshi", null, null, null, null,
                null, null, null, null, "TZS", 11L, false, true);
    }

    private static VatReturnDto vatReturn(VatReturnStatus status, BigDecimal output, BigDecimal netOverride) {
        BigDecimal input = bd("600");
        BigDecimal net = netOverride != null ? netOverride : output.subtract(input);
        BigDecimal closing = net.signum() < 0 ? net.negate() : BigDecimal.ZERO;
        return new VatReturnDto(1L, "VRUID", 5L, "VR-2026-09", (short) 2026, (short) 9,
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), LocalDate.of(2026, 10, 20),
                status, output, input, BigDecimal.ZERO, BigDecimal.ZERO, net, closing, null,
                status == VatReturnStatus.FILED ? "TRA/VAT/1" : null,
                status == VatReturnStatus.FILED ? LocalDate.of(2026, 10, 15) : null,
                null, null, null, null, null, null,
                bd("10500"), null, bd("500"), BigDecimal.ZERO,
                null, false, null, null,
                List.of(new VatReturnBandDto(1L, 1L, "STANDARD", bd("10000"), output),
                        new VatReturnBandDto(2L, 1L, "ZERO_RATED", bd("500"), BigDecimal.ZERO),
                        new VatReturnBandDto(3L, 1L, "EXEMPT", BigDecimal.ZERO, BigDecimal.ZERO)));
    }
}
