package com.erp.modules.documents.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.documents.domain.entity.DocumentBranding;
import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.repository.DocumentBrandingRepository;
import com.erp.modules.documents.repository.DocumentTemplateRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for the startup self-heal added for UAT finding #6.
 *
 * <p>The defect: {@code seedDefaults} only ran when a company was created or when an administrator
 * invoked "Provision defaults" — an action gated on {@code COMPANY.MANAGE}, which no sales role
 * holds. So every company provisioned before the QUOTATION type existed had no proforma template,
 * and the K5 proforma button failed for its users until somebody who knew about an undocumented
 * admin step ran it. Companies 3, 4 and 5 in the UAT database were in exactly that state.
 */
class DocumentBrandingSeederTest {

    private DocumentBrandingRepository brandings;
    private DocumentTemplateRepository templates;
    private CompanyRepository          companies;
    private DocumentBrandingSeeder     seeder;

    @BeforeEach
    void setUp() {
        brandings = mock(DocumentBrandingRepository.class);
        templates = mock(DocumentTemplateRepository.class);
        companies = mock(CompanyRepository.class);
        seeder    = new DocumentBrandingSeeder(brandings, templates, companies);
        // Branding already exists everywhere unless a test says otherwise.
        when(brandings.findByCompanyId(anyLong()))
                .thenReturn(Optional.of(mock(DocumentBranding.class)));
    }

    /** The finding itself: an existing tenant gets its proforma template with no admin involved. */
    @Test
    void healMissingDefaults_createsTheMissingProformaTemplateForAnExistingTenant() {
        // Built before the when(): company() stubs a mock of its own, and a nested when() inside an
        // in-progress stubbing is what Mockito reports as UnfinishedStubbing.
        Company alpha = company(3L, MasterStatus.ACTIVE);
        when(companies.findAll()).thenReturn(List.of(alpha));
        haveEveryTemplateExcept(3L, DocumentType.QUOTATION);

        seeder.healMissingDefaults();

        ArgumentCaptor<DocumentTemplate> saved = ArgumentCaptor.forClass(DocumentTemplate.class);
        verify(templates).save(saved.capture());
        assertThat(saved.getValue().getDocumentType()).isEqualTo(DocumentType.QUOTATION);
        assertThat(saved.getValue().getCompanyId()).isEqualTo(3L);
        assertThat(saved.getValue().getTitle())
                .as("the customer-facing title the K5 proforma prints")
                .isEqualTo("PROFORMA INVOICE");
    }

    /** A heal that ran yesterday must write nothing today — every boot re-runs it. */
    @Test
    void healMissingDefaults_writesNothingWhenEveryTemplateIsAlreadyThere() {
        Company healthy = company(1L, MasterStatus.ACTIVE);
        when(companies.findAll()).thenReturn(List.of(healthy));
        haveEveryTemplateExcept(1L, null);

        seeder.healMissingDefaults();

        verify(templates, never()).save(any());
        verify(brandings, never()).save(any());
    }

    /** An archived tenant renders nothing, so it is not given rows it will never use. */
    @Test
    void healMissingDefaults_skipsArchivedCompanies() {
        Company archived = company(2L, MasterStatus.ARCHIVED);
        when(companies.findAll()).thenReturn(List.of(archived));
        haveEveryTemplateExcept(2L, DocumentType.QUOTATION);

        seeder.healMissingDefaults();

        verify(templates, never()).save(any());
    }

    /**
     * Two instances booting at the same moment can both pass the "is it missing?" check. The loser's
     * insert hits {@code uq_document_template_company_type} (V19); the row it wanted exists, so the
     * violation is swallowed and the remaining companies are still healed.
     */
    @Test
    void healMissingDefaults_toleratesAConcurrentInsertAndKeepsHealingTheRest() {
        Company racing = company(3L, MasterStatus.ACTIVE);
        Company next   = company(4L, MasterStatus.ACTIVE);
        when(companies.findAll()).thenReturn(List.of(racing, next));
        haveEveryTemplateExcept(3L, DocumentType.QUOTATION);
        haveEveryTemplateExcept(4L, DocumentType.QUOTATION);
        when(templates.save(any())).thenAnswer(inv -> {
            DocumentTemplate t = inv.getArgument(0);
            if (t.getCompanyId() == 3L) {
                throw new DataIntegrityViolationException("uq_document_template_company_type");
            }
            return t;
        });

        assertThatCode(() -> seeder.healMissingDefaults()).doesNotThrowAnyException();

        ArgumentCaptor<DocumentTemplate> saved = ArgumentCaptor.forClass(DocumentTemplate.class);
        verify(templates, org.mockito.Mockito.times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(DocumentTemplate::getCompanyId)
                .as("the company after the racing one is still healed")
                .contains(4L);
    }

    /** A self-heal must never stop the application from coming up. */
    @Test
    void healMissingDefaults_survivesAFailureToEvenListCompanies() {
        when(companies.findAll()).thenThrow(new IllegalStateException("database is not reachable"));

        assertThatCode(() -> seeder.healMissingDefaults()).doesNotThrowAnyException();
        verify(templates, never()).save(any());
    }

    /** A tenant with no branding profile at all gets one, named after the company. */
    @Test
    void healMissingDefaults_seedsMissingBrandingToo() {
        Company c = company(5L, MasterStatus.ACTIVE);
        when(c.getName()).thenReturn("Beta Traders");
        List<Company> all = List.of(c);
        when(companies.findAll()).thenReturn(all);
        when(brandings.findByCompanyId(5L)).thenReturn(Optional.empty());
        haveEveryTemplateExcept(5L, null);

        seeder.healMissingDefaults();

        ArgumentCaptor<DocumentBranding> saved = ArgumentCaptor.forClass(DocumentBranding.class);
        verify(brandings).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("Beta Traders");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Stubs the template lookup so every type is present for the company except {@code missing}. */
    private void haveEveryTemplateExcept(Long companyId, DocumentType missing) {
        for (DocumentType type : DocumentType.values()) {
            when(templates.findByCompanyIdAndDocumentType(eq(companyId), eq(type)))
                    .thenReturn(type == missing
                            ? Optional.empty()
                            : Optional.of(mock(DocumentTemplate.class)));
        }
    }

    private static Company company(Long id, MasterStatus status) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getStatus()).thenReturn(status);
        return c;
    }
}
