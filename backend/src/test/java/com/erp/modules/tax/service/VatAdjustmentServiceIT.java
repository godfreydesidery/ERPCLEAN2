package com.erp.modules.tax.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.tax.domain.dto.AddVatAdjustmentRequest;
import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatAdjustmentDto;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
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
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link VatAdjustmentServiceImpl} (ADR-0017 D-3, FR-VAT-05/06, BR-VAT-09).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>addAdjustment on DRAFT — persisted; listByReturn returns it.
 *   <li>removeAdjustment on DRAFT — removed; listByReturn empty.
 *   <li>addAdjustment on FILED return — rejected (BR-VAT-09).
 *   <li>removeAdjustment on FILED return — rejected (BR-VAT-09).
 *   <li>signed effect: INCREASE adds to netVat, DECREASE reduces.
 * </ol>
 */
class VatAdjustmentServiceIT extends PostgresIntegrationTest {

    @Autowired private VatReturnService      vatReturnService;
    @Autowired private VatAdjustmentService  service;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService       glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("VAT Adj IT Org"));
        company    = companies.save(new Company(org, "VATADJ", "VAT Adj IT Co"));
        branch     = branches.save(new Branch(company, "VATADJ1", "VAT Adj IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("vadj_root", passwordEncoder.encode("VadjR00t1!"), "VAdj Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "vadj_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: add on DRAFT persists
    // =========================================================================

    @Test
    void addAdjustment_onDraft_persists() {
        VatReturnDto ret = openDraft(2025, 1);

        VatAdjustmentDto adj = service.addAdjustment(ret.uid(),
                new AddVatAdjustmentRequest(VatAdjustmentReason.OTHER,
                        VatAdjustmentSign.INCREASE, new BigDecimal("500.00"), "Test increase"));

        assertThat(adj.uid()).isNotBlank();
        assertThat(adj.sign()).isEqualTo(VatAdjustmentSign.INCREASE);
        assertThat(adj.amount()).isEqualByComparingTo(new BigDecimal("500.00"));

        List<VatAdjustmentDto> list = service.listByReturn(ret.uid());
        assertThat(list).hasSize(1);
        assertThat(list.get(0).uid()).isEqualTo(adj.uid());
    }

    // =========================================================================
    // Bar 2: remove on DRAFT removes it
    // =========================================================================

    @Test
    void removeAdjustment_onDraft_removes() {
        VatReturnDto ret = openDraft(2025, 2);
        VatAdjustmentDto adj = service.addAdjustment(ret.uid(),
                new AddVatAdjustmentRequest(VatAdjustmentReason.OTHER,
                        VatAdjustmentSign.DECREASE, new BigDecimal("200.00"), "remove me"));

        service.removeAdjustment(ret.uid(), adj.uid());

        assertThat(service.listByReturn(ret.uid())).isEmpty();
    }

    // =========================================================================
    // Bar 3: add on FILED rejected (BR-VAT-09)
    // =========================================================================

    @Test
    void addAdjustment_onFiledReturn_rejected() {
        VatReturnDto ret = openDraft(2025, 3);
        file(ret, "TRA-ADJ-003", LocalDate.of(2025, 4, 15));

        assertThatThrownBy(() ->
                service.addAdjustment(ret.uid(),
                        new AddVatAdjustmentRequest(VatAdjustmentReason.OTHER,
                                VatAdjustmentSign.INCREASE, new BigDecimal("100.00"), "blocked")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been filed")
                .hasMessageContaining("no longer be adjusted");
    }

    // =========================================================================
    // Bar 4: remove on FILED rejected (BR-VAT-09)
    // =========================================================================

    @Test
    void removeAdjustment_onFiledReturn_rejected() {
        VatReturnDto ret = openDraft(2025, 4);
        VatAdjustmentDto adj = service.addAdjustment(ret.uid(),
                new AddVatAdjustmentRequest(VatAdjustmentReason.OTHER,
                        VatAdjustmentSign.INCREASE, new BigDecimal("100.00"), "pre-file adj"));
        file(ret, "TRA-ADJ-004", LocalDate.of(2025, 5, 15));

        assertThatThrownBy(() -> service.removeAdjustment(ret.uid(), adj.uid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FILED");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private VatReturnDto openDraft(int year, int month) {
        return vatReturnService.open(new OpenVatReturnRequest(companyUid, year, month));
    }

    private void file(VatReturnDto ret, String ref, LocalDate date) {
        vatReturnService.file(ret.uid(), new FileVatReturnRequest(ref, date));
    }
}
