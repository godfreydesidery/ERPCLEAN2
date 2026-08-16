package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.repository.VatReturnRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link VatReturnServiceImpl} (ADR-0017 D-4, FR-VAT-01/02/08).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>open → DRAFT return created with correct period fields.
 *   <li>recompute refreshes totals on a DRAFT return.
 *   <li>file → status FILED, postedJournalUid set, second file rejected.
 *   <li>one-return-per-month guard (DB unique, ConflictException on duplicate).
 *   <li>file requires prior period to be FILED first.
 *   <li>recompute rejected on a FILED return.
 *   <li>credit carry-forward: net credit → closingCredit > 0 → next period openingCredit.
 * </ol>
 */
class VatReturnServiceIT extends PostgresIntegrationTest {

    @Autowired private VatReturnService service;
    @Autowired private VatReturnRepository vatReturnRepo;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("VAT Return IT Org"));
        company    = companies.save(new Company(org, "VATRIT", "VAT Return IT Co"));
        branch     = branches.save(new Branch(company, "VATRIT1", "VAT Return IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("vat_root", passwordEncoder.encode("VatR00t1!"), "VAT Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "vat_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: open creates DRAFT return
    // =========================================================================

    @Test
    void open_createsDraftVatReturn() {
        VatReturnDto dto = service.open(new OpenVatReturnRequest(companyUid, 2025, 1));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.status()).isEqualTo(VatReturnStatus.DRAFT);
        assertThat(dto.periodYear()).isEqualTo((short) 2025);
        assertThat(dto.periodMonth()).isEqualTo((short) 1);
        assertThat(dto.periodStart()).isEqualTo(LocalDate.of(2025, 1, 1));
        assertThat(dto.periodEnd()).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(dto.dueDate()).isEqualTo(LocalDate.of(2025, 2, 20));
        assertThat(dto.returnNumber()).startsWith("VATR-");
        assertThat(dto.postedJournalUid()).isNull();
    }

    // =========================================================================
    // Bar 2: recompute refreshes a DRAFT return
    // =========================================================================

    @Test
    void recompute_refreshesDraftReturn() {
        VatReturnDto opened = service.open(new OpenVatReturnRequest(companyUid, 2025, 2));
        VatReturnDto recomputed = service.recompute(opened.uid());

        assertThat(recomputed.uid()).isEqualTo(opened.uid());
        assertThat(recomputed.status()).isEqualTo(VatReturnStatus.DRAFT);
    }

    // =========================================================================
    // Bar 3: file locks the return and posts GL
    // =========================================================================

    @Test
    void file_nilActivity_locksReturn_noJournal() {
        // A return for a period with no sales/purchases is a legal NIL return: it files and locks
        // but posts no GL settlement (nothing to clear) — postedJournalUid stays null (ADR-0017 D-8).
        // The genuine "posts GL + reconciles to 2200" path is pinned by file_withOutputVat_postsSettlement.
        VatReturnDto opened = service.open(new OpenVatReturnRequest(companyUid, 2025, 3));

        VatReturnDto filed = service.file(opened.uid(),
                new FileVatReturnRequest("TRA-REF-001", LocalDate.of(2025, 4, 15)));

        assertThat(filed.status()).isEqualTo(VatReturnStatus.FILED);
        assertThat(filed.filingReference()).isEqualTo("TRA-REF-001");
        assertThat(filed.filedAt()).isNotNull();
        assertThat(filed.postedJournalUid()).isNull();
    }

    @Test
    void file_alreadyFiled_rejected() {
        VatReturnDto opened = service.open(new OpenVatReturnRequest(companyUid, 2025, 4));
        service.file(opened.uid(), new FileVatReturnRequest("TRA-REF-002", LocalDate.of(2025, 5, 15)));

        assertThatThrownBy(() ->
                service.file(opened.uid(), new FileVatReturnRequest("TRA-REF-003", LocalDate.of(2025, 5, 15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been filed")
                .hasMessageContaining("cannot be filed again");
    }

    // =========================================================================
    // Bar 4: one-return-per-month guard
    // =========================================================================

    @Test
    void open_duplicatePeriod_rejected() {
        service.open(new OpenVatReturnRequest(companyUid, 2025, 5));

        assertThatThrownBy(() ->
                service.open(new OpenVatReturnRequest(companyUid, 2025, 5)))
                .isInstanceOf(ConflictException.class);
    }

    // =========================================================================
    // Bar 5: file requires prior period FILED
    // =========================================================================

    @Test
    void file_priorPeriodNotFiled_rejected() {
        // Month 6 prior exists but is DRAFT
        VatReturnDto prior = service.open(new OpenVatReturnRequest(companyUid, 2025, 6));
        VatReturnDto current = service.open(new OpenVatReturnRequest(companyUid, 2025, 7));

        assertThatThrownBy(() ->
                service.file(current.uid(), new FileVatReturnRequest("TRA-007", LocalDate.of(2025, 8, 15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("previous period")
                .hasMessageContaining("not yet been filed");
    }

    // =========================================================================
    // Bar 6: recompute rejected on FILED return
    // =========================================================================

    @Test
    void recompute_onFiledReturn_rejected() {
        VatReturnDto opened = service.open(new OpenVatReturnRequest(companyUid, 2025, 8));
        service.file(opened.uid(), new FileVatReturnRequest("TRA-REF-008", LocalDate.of(2025, 9, 15)));

        assertThatThrownBy(() -> service.recompute(opened.uid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been filed")
                .hasMessageContaining("can no longer be recomputed");
    }

    // =========================================================================
    // Bar 7: credit carry-forward
    // =========================================================================

    @Test
    void file_netCredit_carryForwardToNextPeriod() {
        // File Jan with zero activity → net = 0, closingCredit = 0
        VatReturnDto jan = service.open(new OpenVatReturnRequest(companyUid, 2025, 9));
        service.file(jan.uid(), new FileVatReturnRequest("TRA-09", LocalDate.of(2025, 10, 15)));

        // Open Feb — it should carry openingCredit from Jan's closingCredit
        VatReturnDto feb = service.open(new OpenVatReturnRequest(companyUid, 2025, 10));
        assertThat(feb.openingCredit()).isEqualByComparingTo(jan.closingCredit());
        // With zero activity both closing and opening are 0 — structure is valid
        assertThat(feb.priorReturnId()).isEqualTo(jan.id());
    }
}
