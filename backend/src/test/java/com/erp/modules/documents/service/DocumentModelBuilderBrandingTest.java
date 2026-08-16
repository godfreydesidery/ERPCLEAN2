package com.erp.modules.documents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The printed legal name and TIN fall back to the {@code companies} row when the branding snapshot
 * was never written — and, just as importantly, do NOT when it was written and then cleared.
 *
 * <p>{@code DocumentBrandingSeeder} copies both into {@code document_branding} once, on the pass that
 * creates the row, and never again — so a tenant that filled either in afterwards has it in every
 * standard report header and on no printed document. Without the fallback the only repairs are the
 * Document Branding screen or direct SQL.
 *
 * <p>The suppression half is the one that protects the live customer: {@code ""} is what the Document
 * Branding screen stores when an administrator clears the field, and it is their only way to keep a
 * superseded {@code companies.tax_id} off a tax document.
 */
@ExtendWith(MockitoExtension.class)
class DocumentModelBuilderBrandingTest {

    private static final long COMPANY_ID = 9L;

    @Mock CompanyRepository companies;

    private DocumentModelBuilder builder() {
        return new DocumentModelBuilder(new ObjectMapper(), companies);
    }

    // -------------------------------------------------------------------------
    // Snapshot present → printed as-is
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("branding's own legal name and TIN are printed as-is and the company is never read")
    void brandingSnapshotWins() {
        var model = builder().buildGoodsReceipt(
                receipt(), branding("Kilimanjaro Supermarket Limited", "100-200-300"),
                "GOODS RECEIVED NOTE", "operator");

        assertThat(model.branding().legalName()).isEqualTo("Kilimanjaro Supermarket Limited");
        assertThat(model.branding().taxId()).isEqualTo("100-200-300");
        // The fallback is lazy: a company whose snapshot is intact costs no extra query per render.
        verifyNoInteractions(companies);
    }

    // -------------------------------------------------------------------------
    // Never written (null) → healed from the company
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a never-written TIN falls back to the company's")
    void neverWrittenTaxIdFallsBackToTheCompany() {
        when(companies.findScopedById(COMPANY_ID))
                .thenReturn(Optional.of(company("Kilimanjaro Supermarket Limited", "123-456-789")));

        var model = builder().buildGoodsReceipt(
                receipt(), branding("Kilimanjaro Supermarket Limited", null),
                "GOODS RECEIVED NOTE", "operator");

        assertThat(model.branding().taxId()).isEqualTo("123-456-789");
    }

    /**
     * The half that was missing until a review caught it. A Tanzanian tax invoice must name the
     * registered supplier, so a legal name typed on the Company screen after the branding row was
     * seeded has to reach the page exactly as the TIN does — otherwise the document stays invalid for
     * the one tenant the fallback was written to repair.
     */
    @Test
    @DisplayName("a never-written legal name falls back to the company's")
    void neverWrittenLegalNameFallsBackToTheCompany() {
        when(companies.findScopedById(COMPANY_ID))
                .thenReturn(Optional.of(company("Kilimanjaro Supermarket Limited", "123-456-789")));

        var model = builder().buildGoodsReceipt(
                receipt(), branding(null, "100-200-300"), "GOODS RECEIVED NOTE", "operator");

        assertThat(model.branding().legalName()).isEqualTo("Kilimanjaro Supermarket Limited");
    }

    @Test
    @DisplayName("both missing costs exactly one company query, keyed on the BRANDING ROW's company")
    void bothMissingCostsOneLookupKeyedOnTheBrandingRow() {
        when(companies.findScopedById(COMPANY_ID))
                .thenReturn(Optional.of(company("Kilimanjaro Supermarket Limited", "123-456-789")));

        builder().buildGoodsReceipt(receipt(), branding(null, null), "GOODS RECEIVED NOTE",
                "operator");

        verify(companies, times(1)).findScopedById(COMPANY_ID);
    }

    // -------------------------------------------------------------------------
    // Written and then CLEARED → the company must NOT be substituted
    // -------------------------------------------------------------------------

    /**
     * {@code document-branding.component.ts} sends {@code ""} for every empty field and
     * {@code DocumentBrandingServiceImpl.update} stores it, so a stored blank is an administrator
     * saying "print no TIN". Substituting {@code companies.tax_id} there would put a number they
     * deliberately removed back on the face of every tax invoice, with no way to stop it.
     */
    @ParameterizedTest(name = "a cleared branding TIN [{0}] prints nothing, not the company's")
    @ValueSource(strings = {"", "   "})
    void aClearedTaxIdIsNotResurrectedFromTheCompany(String cleared) {
        var model = builder().buildGoodsReceipt(
                receipt(), branding("Kilimanjaro Supermarket Limited", cleared),
                "GOODS RECEIVED NOTE", "operator");

        // Null and not the blank itself: DocumentPdfRenderer guards on null, so an empty string would
        // print a bare "TIN/VAT:" with nothing after it.
        assertThat(model.branding().taxId()).isNull();
        verifyNoInteractions(companies);
    }

    @ParameterizedTest(name = "a cleared branding legal name [{0}] prints nothing either")
    @ValueSource(strings = {"", "   "})
    void aClearedLegalNameIsNotResurrectedFromTheCompany(String cleared) {
        var model = builder().buildGoodsReceipt(
                receipt(), branding(cleared, "100-200-300"), "GOODS RECEIVED NOTE", "operator");

        assertThat(model.branding().legalName()).isNull();
        verifyNoInteractions(companies);
    }

    // -------------------------------------------------------------------------

    @Test
    @DisplayName("with neither source the block carries null, so no orphan label prints")
    void nothingAnywhereLeavesTheBlockNull() {
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.of(company(null, null)));

        var model = builder().buildGoodsReceipt(
                receipt(), branding(null, null), "GOODS RECEIVED NOTE", "operator");

        assertThat(model.branding().legalName()).isNull();
        assertThat(model.branding().taxId()).isNull();
    }

    @Test
    @DisplayName("a company that cannot be read leaves the block null rather than failing the render")
    void anUnreadableCompanyDoesNotBreakTheRender() {
        when(companies.findScopedById(COMPANY_ID)).thenReturn(Optional.empty());

        var model = builder().buildGoodsReceipt(
                receipt(), branding(null, null), "GOODS RECEIVED NOTE", "operator");

        assertThat(model.branding().taxId()).isNull();
    }

    // -------------------------------------------------------------------------

    private static DocumentBranding branding(String legalName, String taxId) {
        DocumentBranding branding = new DocumentBranding(COMPANY_ID, "Kilimanjaro Supermarket");
        branding.setLegalName(legalName);
        branding.setTaxId(taxId);
        return branding;
    }

    private static Company company(String legalName, String taxId) {
        Company company = new Company(new Organisation("Kilimanjaro Group"), "KS",
                "Kilimanjaro Supermarket");
        company.setLegalName(legalName);
        company.setTaxId(taxId);
        return company;
    }

    /** The smallest document that carries a branding block; the lines play no part here. */
    private static GoodsReceiptPrintDto receipt() {
        return new GoodsReceiptPrintDto(
                "01J000000000000000000000GR", 1L, "GRN00001", "RECEIVED",
                Instant.parse("2026-08-06T09:00:00Z"), "PO-0001",
                "NEW SUPPLIER LTD", null, List.of(), "MWONDOKO", "TZS", null, "RICHARD",
                List.of(), List.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
