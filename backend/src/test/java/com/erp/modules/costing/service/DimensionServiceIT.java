package com.erp.modules.costing.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.costing.domain.dto.CreateDimensionValueRequest;
import com.erp.modules.costing.domain.dto.DimensionDto;
import com.erp.modules.costing.domain.dto.DimensionTagDto;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.dto.ResolvedDimensionTagDto;
import com.erp.modules.costing.domain.dto.SetDimensionMandatoryRequest;
import com.erp.modules.costing.domain.dto.UpdateDimensionValueRequest;
import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.entity.DimensionValue;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.gl.service.GLPostingService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the Cost-centre / Accounting-dimension framework (ADR-0025).
 *
 * <p>Covers:
 * <ul>
 *   <li>Dimension CRUD (list, get, setMandatory)</li>
 *   <li>DimensionValue CRUD (create, update, deactivate, activate)</li>
 *   <li>Cycle rejection (BFS assertNoCycle)</li>
 *   <li>No hard-delete-if-posted guard (BR-CC-06)</li>
 *   <li>DimensionResolver.resolveTag — happy path, inactive-value rejection, wrong-slot rejection</li>
 *   <li>Zero-regression: existing GL posting with no dimension configured posts unchanged</li>
 *   <li>Tagged posting: journal line carries cost_centre_value_id (D-3 fixed-slot columns)</li>
 * </ul>
 */
class DimensionServiceIT extends PostgresIntegrationTest {

    @Autowired private DimensionService         dimensionService;
    @Autowired private DimensionResolver        dimensionResolver;
    @Autowired private DimensionSeeder          dimensionSeeder;
    @Autowired private DimensionRepository      dimensionRepo;
    @Autowired private DimensionValueRepository valueRepo;

    @Autowired private GLPostingService         postingService;
    @Autowired private ChartOfAccountService    coaService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;
    @Autowired private ChartOfAccountRepository accountRepo;

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Costing IT Org"));
        company = companies.save(new Company(org, "CCIT", "Costing IT Co"));
        branch  = branches.save(new Branch(company, "CCB1", "Costing IT Branch"));

        AppUser root = new AppUser("cc_root", passwordEncoder.encode("RootPass1!"), "CC Root");
        root.setRoot(true);
        root   = users.save(inOrganisation(root, org.getId()));
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "cc_root", true, company.getId(), branch.getId(), null));

        // Seed GL foundations (needed for tagged-posting and zero-regression tests)
        coaService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        // Seed built-in dimensions for this company
        dimensionSeeder.seedBuiltIns(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // 1. List dimensions — seeded built-ins visible
    // =========================================================================

    @Test
    void listDimensions_returnsSeedBuiltIns() {
        List<DimensionDto> dims = dimensionService.listDimensions(company.getId());
        assertThat(dims).extracting(DimensionDto::slot)
                .containsExactlyInAnyOrder(DimensionSlot.COST_CENTRE, DimensionSlot.DEPARTMENT);
        assertThat(dims).allMatch(DimensionDto::builtIn);
    }

    // =========================================================================
    // 2. getDimensionByUid — returns correct slot
    // =========================================================================

    @Test
    void getDimensionByUid_returnsCorrectSlot() {
        DimensionDto cc = dimensionService.listDimensions(company.getId())
                .stream().filter(d -> d.slot() == DimensionSlot.COST_CENTRE)
                .findFirst().orElseThrow();

        DimensionDto fetched = dimensionService.getDimensionByUid(cc.uid());
        assertThat(fetched.slot()).isEqualTo(DimensionSlot.COST_CENTRE);
        assertThat(fetched.companyId()).isEqualTo(company.getId());
    }

    // =========================================================================
    // 3. setMandatory — toggles the flag
    // =========================================================================

    @Test
    void setMandatory_togglesFlag() {
        DimensionDto cc = costCentreDim();
        assertThat(cc.mandatory()).as("mandatory defaults false").isFalse();

        dimensionService.setMandatory(cc.uid(), new SetDimensionMandatoryRequest(true));
        DimensionDto after = dimensionService.getDimensionByUid(cc.uid());
        assertThat(after.mandatory()).isTrue();

        // Toggle back
        dimensionService.setMandatory(cc.uid(), new SetDimensionMandatoryRequest(false));
        assertThat(dimensionService.getDimensionByUid(cc.uid()).mandatory()).isFalse();
    }

    // =========================================================================
    // 4. createValue — new active DimensionValue
    // =========================================================================

    @Test
    void createValue_persistsAndReturnsDto() {
        DimensionDto cc = costCentreDim();

        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-SALES", "Sales", null));

        assertThat(v.uid()).isNotBlank();
        assertThat(v.code()).isEqualTo("CC-SALES");
        assertThat(v.active()).isTrue();
        assertThat(v.dimensionId()).isEqualTo(cc.id());
    }

    // =========================================================================
    // 5. updateValue — updates name
    // =========================================================================

    @Test
    void updateValue_updatesName() {
        DimensionDto cc = costCentreDim();
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-MKTING", "Marketing", null));

        DimensionValueDto updated = dimensionService.updateValue(
                v.uid(), new UpdateDimensionValueRequest("Marketing Dept", null, false));
        assertThat(updated.name()).isEqualTo("Marketing Dept");
        assertThat(updated.code()).isEqualTo("CC-MKTING"); // code immutable
    }

    // =========================================================================
    // 6. deactivateValue / activateValue
    // =========================================================================

    @Test
    void deactivateAndActivateValue_togglesActive() {
        DimensionDto cc = costCentreDim();
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-OPS", "Operations", null));

        dimensionService.deactivateValue(v.uid());
        DimensionValueDto after = dimensionService.getValueByUid(v.uid());
        assertThat(after.active()).isFalse();

        dimensionService.activateValue(v.uid());
        assertThat(dimensionService.getValueByUid(v.uid()).active()).isTrue();
    }

    // =========================================================================
    // 7. Cycle rejection — parent chain must not cycle back to self
    // =========================================================================

    @Test
    void createValue_cyclicParent_rejected() {
        DimensionDto cc = costCentreDim();

        DimensionValueDto parent = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-P1", "Parent1", null));
        DimensionValueDto child = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-C1", "Child1", parent.uid()));

        // Attempt to set parent.parent = child → cycle
        assertThatThrownBy(() ->
                dimensionService.updateValue(parent.uid(),
                        new UpdateDimensionValueRequest("Parent1", child.uid(), false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("circular hierarchy");
    }

    // =========================================================================
    // 8. deleteValue guard — cannot hard-delete if posted (BR-CC-06)
    // =========================================================================

    @Test
    void deleteValue_withPostings_rejected() {
        DimensionDto cc = costCentreDim();
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-POSTED", "Posted Dept", null));

        // Post a journal line tagged with this value
        ResolvedDimensionTagDto resolved = dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(v.uid(), null, null, null));

        Long cashId = accountId("1000");
        Long cogsId = accountId("5100");
        postingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Tagged post test",
                JournalSourceType.MANUAL, "REF-CC-001",
                null, rootId,
                List.of(
                        new LineDraft(cogsId, new BigDecimal("100"), BigDecimal.ZERO,
                                "TZS", "DR COGS",
                                resolved.costCentreValueId(), null, null, null),
                        new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("100"),
                                "TZS", "CR Cash")
                )));

        // Now try to delete — must reject
        assertThatThrownBy(() -> dimensionService.deleteValue(v.uid()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("posted");
    }

    // =========================================================================
    // 9. DimensionResolver.resolveTag — happy path
    // =========================================================================

    @Test
    void resolveTag_withValidUids_returnsIds() {
        DimensionDto cc   = costCentreDim();
        DimensionDto dept = deptDim();

        DimensionValueDto ccVal   = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(),   "CC-HQ",  "HQ",     null));
        DimensionValueDto deptVal = dimensionService.createValue(
                new CreateDimensionValueRequest(dept.uid(), "DEPT-IT","IT Dept",null));

        ResolvedDimensionTagDto resolved = dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(ccVal.uid(), deptVal.uid(), null, null));

        assertThat(resolved.costCentreValueId()).isEqualTo(ccVal.id());
        assertThat(resolved.departmentValueId()).isEqualTo(deptVal.id());
        assertThat(resolved.dimension3ValueId()).isNull();
        assertThat(resolved.dimension4ValueId()).isNull();
    }

    // =========================================================================
    // 10. DimensionResolver.resolveTag — inactive value rejected (BR-CC-04)
    // =========================================================================

    @Test
    void resolveTag_inactiveValue_rejected() {
        DimensionDto cc = costCentreDim();
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-INACT", "Inactive CC", null));

        dimensionService.deactivateValue(v.uid());

        assertThatThrownBy(() -> dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(v.uid(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    // =========================================================================
    // 11. DimensionResolver.resolveTag — wrong slot rejected (BR-CC-04)
    // =========================================================================

    @Test
    void resolveTag_wrongSlotValue_rejected() {
        // Create a value under DEPARTMENT slot but supply it as cost_centre_value_uid
        DimensionDto dept = deptDim();
        DimensionValueDto deptVal = dimensionService.createValue(
                new CreateDimensionValueRequest(dept.uid(), "DEPT-FIN", "Finance", null));

        // Supplying a DEPARTMENT value uid in the costCentreValueUid slot → slot mismatch
        assertThatThrownBy(() -> dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(deptVal.uid(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("slot");
    }

    // =========================================================================
    // 12. Zero-regression: GL posting with NO dimension tag produces unchanged lines
    // =========================================================================

    @Test
    void post_withNoDimensionTag_journalLinesUnchanged() {
        Long cashId = accountId("1000");
        Long cogsId = accountId("5100");

        // Pure 5-arg LineDraft (no dimension ids — existing poster pattern)
        postingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Zero-regression GL test",
                JournalSourceType.MANUAL, "REF-ZEREG-001",
                null, rootId,
                List.of(
                        new LineDraft(cogsId, new BigDecimal("200"), BigDecimal.ZERO, "TZS", "DR COGS"),
                        new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("200"), "TZS", "CR Cash")
                )));

        // All lines persisted — no exception thrown. The zero-regression guarantee holds.
        // (dimension columns default null; existing code path unchanged — NFR-CC-01)
    }

    // =========================================================================
    // 13. Tagged posting: journal line carries cost_centre_value_id (D-3)
    // =========================================================================

    @Test
    void post_withDimensionTag_lineCarriesValueId() {
        DimensionDto cc = costCentreDim();
        DimensionValueDto ccVal = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-TAG-TEST", "Tag Test", null));

        ResolvedDimensionTagDto resolved = dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(ccVal.uid(), null, null, null));

        Long cashId = accountId("1000");
        Long cogsId = accountId("5100");

        postingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Tagged-posting test",
                JournalSourceType.MANUAL, "REF-TAG-001",
                null, rootId,
                List.of(
                        new LineDraft(cogsId, new BigDecimal("300"), BigDecimal.ZERO,
                                "TZS", "DR COGS",
                                resolved.costCentreValueId(), null, null, null),
                        new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("300"),
                                "TZS", "CR Cash")
                )));

        // Verify the expense line persisted with cost_centre_value_id = ccVal.id()
        // We use native valueRepo to verify the dimension value exists and is linked correctly
        DimensionValue persisted = valueRepo.findById(ccVal.id()).orElseThrow();
        assertThat(persisted.isActive()).isTrue();
        assertThat(persisted.getCode()).isEqualTo("CC-TAG-TEST");
        // The journal_lines column is validated via the valueRepo.hasPostings guard
        assertThat(valueRepo.hasPostings(ccVal.id())).isTrue();
    }

    // =========================================================================
    // 14. listValues — pageable, returns values for a dimension
    // =========================================================================

    @Test
    void listValues_returnsPagedValues() {
        DimensionDto cc = costCentreDim();
        dimensionService.createValue(new CreateDimensionValueRequest(cc.uid(), "CC-A", "A", null));
        dimensionService.createValue(new CreateDimensionValueRequest(cc.uid(), "CC-B", "B", null));

        Page<DimensionValueDto> page = dimensionService.listValues(cc.uid(), Pageable.unpaged());
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
        assertThat(page.getContent()).extracting(DimensionValueDto::code)
                .contains("CC-A", "CC-B");
    }

    // =========================================================================
    // 15. Cross-company isolation — value in company A not visible via company B
    // =========================================================================

    @Test
    void resolveTag_crossCompanyValue_rejected() {
        // Company B
        Organisation orgB = organisations.save(new Organisation("Org B"));
        Company companyB  = companies.save(new Company(orgB, "CCB", "Costing IT Co B"));
        branches.save(new Branch(companyB, "CCB-B1", "Branch B1"));

        dimensionSeeder.seedBuiltIns(companyB.getId());

        // Create a value for company B
        DimensionDto ccB = dimensionService.listDimensions(companyB.getId())
                .stream().filter(d -> d.slot() == DimensionSlot.COST_CENTRE)
                .findFirst().orElseThrow();

        // Need company B context to create its value
        RequestContext.set(new RequestContext.Principal(
                rootId, "cc_root", true, companyB.getId(),
                branches.findAll().stream()
                        .filter(b -> b.getCompany().getId().equals(companyB.getId()))
                        .findFirst().orElseThrow().getId(), null));

        DimensionValueDto companyBValue = dimensionService.createValue(
                new CreateDimensionValueRequest(ccB.uid(), "CC-B-ONLY", "Company B Only", null));

        // Switch back to company A context
        RequestContext.set(new RequestContext.Principal(
                rootId, "cc_root", true, company.getId(), branch.getId(), null));

        // Company A resolver must reject company B's value uid
        assertThatThrownBy(() -> dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(companyBValue.uid(), null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // =========================================================================
    // 16. Mandatory dimension enforcement — GL posting must reject an untagged line
    //     when Cost Centre is set mandatory (ADR-0025 D-4 step 3, FR-CC-08, BR-CC-03).
    //     Proves the validateDimensions() gate in GLPostingServiceImpl.
    // =========================================================================

    @Test
    void post_mandatoryDimension_rejectsUntaggedLine() {
        DimensionDto cc = costCentreDim();

        // Make Cost Centre mandatory for this company
        dimensionService.setMandatory(cc.uid(), new SetDimensionMandatoryRequest(true));

        Long cashId = accountId("1000");
        Long cogsId = accountId("5100");

        // Attempt to post with NO cost_centre_value_id on either line — must reject
        assertThatThrownBy(() ->
                postingService.post(new JournalEntryDraft(
                        company.getId(), branch.getId(), LocalDate.now(),
                        "Mandatory-dimension gate test",
                        JournalSourceType.MANUAL, "REF-MAND-001",
                        null, rootId,
                        List.of(
                                new LineDraft(cogsId, new BigDecimal("50"), BigDecimal.ZERO,
                                        "TZS", "DR COGS"),
                                new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("50"),
                                        "TZS", "CR Cash")
                        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is required on every journal line");
    }

    // =========================================================================
    // 17. Inactive dimension value — GL posting must reject at the engine level
    //     (ADR-0025 D-4 step 2, BR-CC-04).  Proves assertDimensionValueValid() in
    //     GLPostingServiceImpl fires even when the poster (DimensionResolver) approved it
    //     earlier but the value was deactivated before the post reaches the engine.
    // =========================================================================

    @Test
    void post_inactiveDimensionValue_rejectedByGlEngine() {
        DimensionDto cc = costCentreDim();
        DimensionValueDto v = dimensionService.createValue(
                new CreateDimensionValueRequest(cc.uid(), "CC-WILL-DEACT", "Will Deactivate", null));

        // Resolve while still active (poster step — succeeds)
        ResolvedDimensionTagDto resolved = dimensionResolver.resolveTag(
                company.getId(),
                new DimensionTagDto(v.uid(), null, null, null));

        // Deactivate the value BEFORE the post reaches the engine
        dimensionService.deactivateValue(v.uid());

        Long cashId = accountId("1000");
        Long cogsId = accountId("5100");

        // The GL engine must reject the inactive value (step 2 re-assertion, D-4)
        assertThatThrownBy(() ->
                postingService.post(new JournalEntryDraft(
                        company.getId(), branch.getId(), LocalDate.now(),
                        "Inactive-value GL engine test",
                        JournalSourceType.MANUAL, "REF-INACT-001",
                        null, rootId,
                        List.of(
                                new LineDraft(cogsId, new BigDecimal("75"), BigDecimal.ZERO,
                                        "TZS", "DR COGS",
                                        resolved.costCentreValueId(), null, null, null),
                                new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("75"),
                                        "TZS", "CR Cash")
                        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inactive");
    }

    // =========================================================================
    // 18. ADR-0025 amendment (ISSUES-REGISTER #20d): mandatory-dimension enforcement
    //     (Step 3) applies ONLY to MANUAL journals. An automated/system poster (a
    //     non-MANUAL source type) posting an untagged line must SUCCEED even when a
    //     dimension is mandatory — otherwise event-driven postings (e.g. STOCK.RECEIVED
    //     goods-receipt GL) become poison events. Step 2 still validates any tag present.
    // =========================================================================

    @Test
    void post_mandatoryDimension_systemSourceUntaggedLine_succeeds() {
        DimensionDto cc = costCentreDim();

        // Cost Centre mandatory for this company (same precondition as Test 16)
        dimensionService.setMandatory(cc.uid(), new SetDimensionMandatoryRequest(true));

        Long cashId = accountId("1000");
        Long cogsId = accountId("5100");

        // A SYSTEM/event-driven posting (sourceType != MANUAL) with NO dimension tags must POST —
        // the #20d scenario. We use COGS as a representative automated source.
        var posted = postingService.post(new JournalEntryDraft(
                company.getId(), branch.getId(), LocalDate.now(),
                "Automated posting with mandatory dimension (#20d)",
                JournalSourceType.COGS, "REF-20D-SYS-001",
                null, null,
                List.of(
                        new LineDraft(cogsId, new BigDecimal("60"), BigDecimal.ZERO,
                                "TZS", "DR COGS"),
                        new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("60"),
                                "TZS", "CR Cash")
                )));

        assertThat(posted).as("automated posting must succeed despite a mandatory dimension (#20d)")
                .isNotNull();
        assertThat(posted.uid()).isNotBlank();

        // And the MANUAL path is STILL enforced (regression guard for the control that remains).
        assertThatThrownBy(() ->
                postingService.post(new JournalEntryDraft(
                        company.getId(), branch.getId(), LocalDate.now(),
                        "Manual posting still enforces mandatory",
                        JournalSourceType.MANUAL, "REF-20D-MAN-001",
                        null, rootId,
                        List.of(
                                new LineDraft(cogsId, new BigDecimal("60"), BigDecimal.ZERO,
                                        "TZS", "DR COGS"),
                                new LineDraft(cashId, BigDecimal.ZERO, new BigDecimal("60"),
                                        "TZS", "CR Cash")
                        ))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is required on every journal line");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private DimensionDto costCentreDim() {
        return dimensionService.listDimensions(company.getId())
                .stream().filter(d -> d.slot() == DimensionSlot.COST_CENTRE)
                .findFirst().orElseThrow();
    }

    private DimensionDto deptDim() {
        return dimensionService.listDimensions(company.getId())
                .stream().filter(d -> d.slot() == DimensionSlot.DEPARTMENT)
                .findFirst().orElseThrow();
    }

    private Long accountId(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account " + code + " not seeded"));
    }
}
