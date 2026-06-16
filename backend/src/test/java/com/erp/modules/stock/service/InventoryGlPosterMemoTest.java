package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Regression tests for FOLLOW-001: journal memo text must use human-readable document numbers,
 * never raw 26-char ULIDs.
 *
 * <p>A ULID is exactly 26 alphanumeric chars (Crockford base32). The tests assert that no
 * description / memo field captured by GLPostingService contains a 26-char ULID-shaped token.
 */
class InventoryGlPosterMemoTest {

    /** Pattern that matches a 26-character Crockford-base32 ULID (upper or lower). */
    private static final java.util.regex.Pattern ULID_PATTERN =
            java.util.regex.Pattern.compile("[0-9A-Za-z]{26}");

    private GLPostingService glPostingService;
    private GLConfigResolver configResolver;
    private InventoryGlPoster poster;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 2L;

    @BeforeEach
    void setUp() {
        glPostingService = mock(GLPostingService.class);
        configResolver   = mock(GLConfigResolver.class);
        poster           = new InventoryGlPoster(glPostingService, configResolver);

        // Stub GL config to return a minimal ChartOfAccount for any key
        when(configResolver.resolve(any(), any(GlConfigKey.class)))
                .thenAnswer(inv -> {
                    ChartOfAccount coa = mock(ChartOfAccount.class);
                    when(coa.getId()).thenReturn(100L);
                    return coa;
                });

        // Stub posting to return a fake JournalEntryDto
        when(glPostingService.post(any())).thenAnswer(inv -> {
            JournalEntryDto dto = mock(JournalEntryDto.class);
            when(dto.uid()).thenReturn("ENTRY-UID");
            return dto;
        });
    }

    // -------------------------------------------------------------------------
    // FOLLOW-001: Goods receipt memo uses GRN number, not uid ULID
    // -------------------------------------------------------------------------

    @Test
    void postReceiptInNewTx_headerAndLineMemos_useGrnNumber_notUlid() {
        String receiptUid    = "01JZZZZZZZZZZZZZZZZZZZZZZ1"; // 26-char ULID
        String receiptNumber = "GRN-0042";
        String grLineUid     = "01JZGRLINE0123456789ABCDEF"; // 26-char ULID — must NOT leak into the memo

        List<InventoryGlPoster.ReceiptLeg> legs = List.of(
                new InventoryGlPoster.ReceiptLeg(grLineUid, "RICE-5KG", new BigDecimal("500.00")));

        poster.postReceiptInNewTx(COMPANY_ID, BRANCH_ID, LocalDate.now(),
                receiptUid, receiptNumber, "TZS", legs);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = forClass(JournalEntryDraft.class);
        org.mockito.Mockito.verify(glPostingService).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();

        // Header description: GRN number, no ULID (existing guarantee).
        assertThat(draft.description())
                .as("journal description should contain GRN number")
                .contains(receiptNumber);
        assertThat(draft.description())
                .as("journal description must NOT contain the raw 26-char ULID")
                .doesNotContain(receiptUid);
        assertNoUlidIn("journal description", draft.description());

        // LINE memos: this was the FOLLOW-001 gap — the GR-line uid was rendered as
        // "GR line <uid> — <code>". Memos must now use product code + GRN number, no uid.
        assertThat(draft.lines()).as("receipt posts INVENTORY + GRNI legs").isNotEmpty();
        for (JournalEntryDraft.LineDraft line : draft.lines()) {
            assertThat(line.lineMemo())
                    .as("line memo should be human-readable (product code + GRN number)")
                    .contains("RICE-5KG")
                    .contains(receiptNumber);
            assertThat(line.lineMemo())
                    .as("line memo must NOT contain the raw GR-line uid")
                    .doesNotContain(grLineUid)
                    .doesNotContain(receiptUid);
            assertNoUlidIn("receipt line memo", line.lineMemo());
        }
    }

    @Test
    void postReceiptInNewTx_nullReceiptNumber_fallsBackToUid_noExceptionThrown() {
        // When receiptNumber is null (old events in flight) the poster must not crash;
        // it falls back to the uid in the memo. This is tolerable for in-flight events.
        String receiptUid = "01AAAAAAAAAAAAAAAAAAAAAA01";

        List<InventoryGlPoster.ReceiptLeg> legs = List.of(
                new InventoryGlPoster.ReceiptLeg("L1", "CODE", new BigDecimal("100")));

        // Should not throw
        org.assertj.core.api.Assertions.assertThatCode(
                () -> poster.postReceiptInNewTx(COMPANY_ID, BRANCH_ID, LocalDate.now(),
                        receiptUid, null, "TZS", legs))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // FOLLOW-001: Sale COGS memo uses invoice number, not uid ULID
    // -------------------------------------------------------------------------

    @Test
    void postCogsInNewTx_descriptionContainsInvoiceNumber_notUlid() {
        String invoiceUid    = "01JZZZZZZZZZZZZZZZZZZZZZ02"; // 26-char ULID
        String invoiceNumber = "INV-0099";

        List<InventoryGlPoster.CogsLeg> legs = List.of(
                new InventoryGlPoster.CogsLeg(5L, "PROD-A", new BigDecimal("250.00")));

        poster.postCogsInNewTx(COMPANY_ID, BRANCH_ID, LocalDate.now(),
                invoiceUid, invoiceNumber, "TZS", legs);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = forClass(JournalEntryDraft.class);
        org.mockito.Mockito.verify(glPostingService).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();

        assertThat(draft.description())
                .as("COGS journal description should contain invoice number")
                .contains(invoiceNumber);
        assertThat(draft.description())
                .as("COGS journal description must NOT contain the raw 26-char ULID")
                .doesNotContain(invoiceUid);
        assertNoUlidIn("COGS journal description", draft.description());
    }

    // -------------------------------------------------------------------------
    // FOLLOW-001: Adjustment memo uses productCode/reasonCode, not movement ULID
    // -------------------------------------------------------------------------

    @Test
    void postAdjustmentDirect_descriptionContainsProductCodeAndReason_notUlid() {
        String movementUid  = "01JZZZZZZZZZZZZZZZZZZZZZ03"; // 26-char ULID — sourceRef only
        String productCode  = "FLOUR-2KG";
        String reasonCode   = "DAMAGE";

        InventoryGlPoster.AdjustmentPostCmd cmd = new InventoryGlPoster.AdjustmentPostCmd(
                movementUid, "TZS", new BigDecimal("100.00"), true, 1L,
                null, null, productCode, reasonCode);

        poster.postAdjustmentDirect(COMPANY_ID, BRANCH_ID, LocalDate.now(), cmd);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = forClass(JournalEntryDraft.class);
        org.mockito.Mockito.verify(glPostingService).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();

        assertThat(draft.description())
                .as("adjustment journal description should contain product code")
                .contains(productCode);
        assertThat(draft.description())
                .as("adjustment journal description should contain reason code")
                .contains(reasonCode);
        assertThat(draft.description())
                .as("adjustment journal description must NOT contain the raw 26-char movement ULID")
                .doesNotContain(movementUid);
        assertNoUlidIn("adjustment journal description", draft.description());

        // sourceRef on the draft must still be the uid (machine linkage)
        assertThat(draft.sourceRef())
                .as("sourceRef must remain the uid for machine linkage")
                .isEqualTo(movementUid);
    }

    @Test
    void postAdjustmentDirect_stockCountJournal_usesCountNumberDescriptor() {
        // Simulates StockCountServiceImpl: sourceRef = count.uid, productCode = count.countNumber,
        // reasonCode = "COUNT_CORRECTION" — as changed in FOLLOW-001.
        String countUid    = "01JZZZZZZZZZZZZZZZZZZZZZ04"; // 26-char ULID — sourceRef
        String countNumber = "CNT-0007";

        InventoryGlPoster.AdjustmentPostCmd cmd = new InventoryGlPoster.AdjustmentPostCmd(
                countUid, "TZS", new BigDecimal("50.00"), false, 1L,
                null, null, countNumber, "COUNT_CORRECTION");

        poster.postAdjustmentDirect(COMPANY_ID, BRANCH_ID, LocalDate.now(), cmd);

        ArgumentCaptor<JournalEntryDraft> draftCaptor = forClass(JournalEntryDraft.class);
        org.mockito.Mockito.verify(glPostingService).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();

        assertThat(draft.description()).contains(countNumber);
        assertThat(draft.description()).doesNotContain(countUid);
        assertThat(draft.sourceRef()).isEqualTo(countUid);
    }

    // -------------------------------------------------------------------------
    // FOLLOW-001: Purchase return memo uses return number, not uid ULID
    // -------------------------------------------------------------------------

    @Test
    void postPurchaseReturnInNewTx_descriptionContainsReturnNumber_notUlid() {
        String returnUid    = "01JZZZZZZZZZZZZZZZZZZZZZ05";
        String returnNumber = "PRN-0003";

        poster.postPurchaseReturnInNewTx(COMPANY_ID, BRANCH_ID, LocalDate.now(),
                returnUid, returnNumber, "TZS", new BigDecimal("300.00"));

        ArgumentCaptor<JournalEntryDraft> draftCaptor = forClass(JournalEntryDraft.class);
        org.mockito.Mockito.verify(glPostingService).post(draftCaptor.capture());
        JournalEntryDraft draft = draftCaptor.getValue();

        assertThat(draft.description()).contains(returnNumber);
        assertThat(draft.description()).doesNotContain(returnUid);
        assertNoUlidIn("purchase return journal description", draft.description());
        assertThat(draft.sourceRef()).isEqualTo(returnUid);
    }

    // -------------------------------------------------------------------------
    // Helper: assert no 26-char ULID-shaped token appears in the given text.
    // A description may legitimately contain shorter IDs or product codes, but never a ULID.
    // -------------------------------------------------------------------------

    private void assertNoUlidIn(String label, String text) {
        if (text == null) return;
        // Split on whitespace and common delimiters, check each token
        for (String token : text.split("[\\s/—\\-]+")) {
            if (ULID_PATTERN.matcher(token).matches()) {
                org.assertj.core.api.Assertions.fail(
                        label + " contains a 26-char ULID token '" + token + "' in: " + text);
            }
        }
    }
}
