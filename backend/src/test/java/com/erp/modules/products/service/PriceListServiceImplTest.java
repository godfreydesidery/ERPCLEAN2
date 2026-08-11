package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.UpdatePriceListRequest;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PriceListServiceImpl} — error-message-hygiene defect D2: creating a price
 * list with a code that already exists in the company must throw a friendly {@link ConflictException}
 * naming the clashing code, pre-checked before save (real unique key: {@code uq_price_list_company_code}
 * = company_id + code), never the generic DB-constraint catch-all.
 */
class PriceListServiceImplTest {

    private static final Long COMPANY_ID = 10L;
    private static final String COMPANY_UID = "COMPANY-UID-1";

    private PriceListRepository priceLists;
    private CompanyRepository companies;
    private ScopeGuard scopeGuard;
    private AuditService audit;
    private PriceListServiceImpl service;

    @BeforeEach
    void setUp() {
        priceLists = mock(PriceListRepository.class);
        companies = mock(CompanyRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit = mock(AuditService.class);
        service = new PriceListServiceImpl(priceLists, companies, scopeGuard, audit);

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));

        RequestContext.set(new RequestContext.Principal(1L, "tester@test.com", false, COMPANY_ID, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void create_duplicateCode_throwsFriendlyConflict() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "RETAIL")).thenReturn(true);

        CreatePriceListRequest req = new CreatePriceListRequest(COMPANY_UID, "RETAIL", "Retail Price List");

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ConflictException.class)
                .hasMessage("A price list with code RETAIL already exists.");

        verify(priceLists, never()).save(any(PriceList.class));
    }

    @Test
    void create_newCode_savesSuccessfully() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "WHOLESALE")).thenReturn(false);
        PriceList saved = new PriceList(COMPANY_ID, "WHOLESALE", "Wholesale Price List", 1L);
        when(priceLists.save(any(PriceList.class))).thenReturn(saved);

        CreatePriceListRequest req = new CreatePriceListRequest(COMPANY_UID, "WHOLESALE", "Wholesale Price List");

        service.create(req);

        verify(priceLists).save(any(PriceList.class));
    }

    // -------------------------------------------------------------------------
    // ADR-0056 D-2 — the service/DB default stays EXCLUSIVE (grandfathering + import safety);
    // "inclusive by default for new lists" is a UI affordance (the create form pre-checks the
    // toggle and sends priceIncludesVat=true explicitly). A request that OMITS the flag keeps
    // the entity default (false), so existing lists and direct-API/import callers are never
    // silently reinterpreted as gross.
    // -------------------------------------------------------------------------

    @Test
    void create_omittingPriceIncludesVat_keepsExclusiveEntityDefault() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "RETAIL2")).thenReturn(false);
        when(priceLists.save(any(PriceList.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatePriceListRequest req = new CreatePriceListRequest(COMPANY_UID, "RETAIL2", "Retail 2");

        PriceList saved = captureSavedPriceList(req);

        assertThat(saved.isPriceIncludesVat()).isFalse();
    }

    @Test
    void create_explicitFalsePriceIncludesVat_keepsExclusive() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "WHOLESALE2")).thenReturn(false);
        when(priceLists.save(any(PriceList.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatePriceListRequest req = new CreatePriceListRequest(COMPANY_UID, "WHOLESALE2", "Wholesale 2",
                null, null, null, false, null, null);

        PriceList saved = captureSavedPriceList(req);

        assertThat(saved.isPriceIncludesVat()).isFalse();
    }

    @Test
    void create_explicitTruePriceIncludesVat_staysInclusive() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "RETAIL3")).thenReturn(false);
        when(priceLists.save(any(PriceList.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatePriceListRequest req = new CreatePriceListRequest(COMPANY_UID, "RETAIL3", "Retail 3",
                null, null, null, true, null, null);

        PriceList saved = captureSavedPriceList(req);

        assertThat(saved.isPriceIncludesVat()).isTrue();
    }

    // -------------------------------------------------------------------------
    // The company default — exactly one, or the question has no answer
    // -------------------------------------------------------------------------
    // The stock reports resolve selling prices from the company's default list. Nothing SET the flag
    // before this action existed, so most companies have none and the reports print blank prices;
    // and because the flag has always been bindable on create/update, a company could just as easily
    // end up with two, at which point "the default list" depends on row order.
    // -------------------------------------------------------------------------

    @Test
    void setDefault_clearsTheFlagOnTheCompanysOtherLists() {
        PriceList target = activeList("WHOLESALE", "Wholesale");
        PriceList previous = flaggedOtherList();
        when(priceLists.findByUid("PL-UID-1")).thenReturn(Optional.of(target));
        when(priceLists.findDefaultsOfCompany(COMPANY_ID)).thenReturn(List.of(previous));

        PriceListDto dto = service.setDefaultByUid("PL-UID-1");

        assertThat(dto.isDefault()).isTrue();
        verify(previous)
                .setDefault(false);
    }

    /**
     * No partial unique index stands behind {@code is_default}, so a company can arrive here already
     * holding several flagged rows. Clearing all of them is what makes the next set self-healing.
     */
    @Test
    void setDefault_clearsEveryPreviouslyFlaggedList_notJustTheFirst() {
        PriceList target = activeList("WHOLESALE", "Wholesale");
        PriceList first = flaggedOtherList();
        PriceList second = flaggedOtherList();
        when(priceLists.findByUid("PL-UID-1")).thenReturn(Optional.of(target));
        when(priceLists.findDefaultsOfCompany(COMPANY_ID)).thenReturn(List.of(first, second));

        service.setDefaultByUid("PL-UID-1");

        verify(first).setDefault(false);
        verify(second).setDefault(false);
    }

    @Test
    void setDefault_onAnArchivedList_throwsAFriendlyConflict() {
        PriceList archived = activeList("OLD", "Old Prices");
        archived.setStatus(MasterStatus.ARCHIVED);
        when(priceLists.findByUid("PL-UID-2")).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> service.setDefaultByUid("PL-UID-2"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only an active price list can be made the default. "
                        + "Restore this list first.");

        verify(priceLists, never()).findDefaultsOfCompany(COMPANY_ID);
    }

    /**
     * The flag has been bindable on create since the column shipped. Without clearing here, the
     * dedicated action's invariant is bypassable by the very path that could break it.
     */
    @Test
    void create_askingToBeTheDefault_clearsTheExistingOne() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "NEW")).thenReturn(false);
        when(priceLists.save(any(PriceList.class))).thenAnswer(inv -> inv.getArgument(0));
        PriceList previous = flaggedOtherList();
        when(priceLists.findDefaultsOfCompany(COMPANY_ID)).thenReturn(List.of(previous));

        service.create(new CreatePriceListRequest(COMPANY_UID, "NEW", "New List",
                null, null, null, null, true, null));

        verify(previous).setDefault(false);
    }

    @Test
    void create_notAskingToBeTheDefault_leavesTheExistingOneAlone() {
        when(priceLists.existsByCompanyIdAndCode(COMPANY_ID, "PLAIN")).thenReturn(false);
        when(priceLists.save(any(PriceList.class))).thenAnswer(inv -> inv.getArgument(0));

        service.create(new CreatePriceListRequest(COMPANY_UID, "PLAIN", "Plain List"));

        verify(priceLists, never()).findDefaultsOfCompany(COMPANY_ID);
    }

    @Test
    void update_askingToBeTheDefault_clearsTheExistingOne() {
        PriceList target = activeList("WHOLESALE", "Wholesale");
        when(priceLists.findByUid("PL-UID-3")).thenReturn(Optional.of(target));
        PriceList previous = flaggedOtherList();
        when(priceLists.findDefaultsOfCompany(COMPANY_ID)).thenReturn(List.of(previous));

        service.updateByUid("PL-UID-3", new UpdatePriceListRequest("Wholesale Renamed",
                null, null, null, null, true, null));

        verify(previous).setDefault(false);
        assertThat(target.isDefault()).isTrue();
    }

    // -------------------------------------------------------------------------
    // A partial update must not quietly discard what it does not mention
    // -------------------------------------------------------------------------
    // Every metadata field on the update request is optional, and the two forms that actually call
    // this endpoint send a subset: the rename form sends {name, priceIncludesVat}, the set-default
    // button sends {name, isDefault}. currency/effectiveFrom/effectiveTo were written
    // unconditionally, so both of those wiped all three — and because a missing currency parses to
    // null rather than being rejected, no 400 ever surfaced to say so.
    // -------------------------------------------------------------------------

    @Test
    void update_omittingCurrencyAndDates_leavesThemIntact() {
        PriceList target = activeList("RETAIL", "Retail");
        target.setCurrency(CurrencyCode.of("TZS"));
        target.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        target.setEffectiveTo(LocalDate.of(2026, 12, 31));
        when(priceLists.findByUid("PL-UID-4")).thenReturn(Optional.of(target));

        // What the rename form sends: a name and the VAT toggle, nothing else.
        service.updateByUid("PL-UID-4", new UpdatePriceListRequest("Retail Renamed",
                null, null, null, true, null, null));

        assertThat(target.getName()).isEqualTo("Retail Renamed");
        assertThat(target.isPriceIncludesVat()).isTrue();
        assertThat(target.getCurrency()).isEqualTo(CurrencyCode.of("TZS"));
        assertThat(target.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(target.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
    }

    /** The set-default button sends {name, isDefault} — the same partial shape, same requirement. */
    @Test
    void update_settingOnlyTheDefaultFlag_leavesCurrencyAndDatesIntact() {
        PriceList target = activeList("WHOLESALE", "Wholesale");
        target.setCurrency(CurrencyCode.of("USD"));
        target.setEffectiveFrom(LocalDate.of(2026, 3, 1));
        when(priceLists.findByUid("PL-UID-5")).thenReturn(Optional.of(target));
        when(priceLists.findDefaultsOfCompany(COMPANY_ID)).thenReturn(List.of());

        service.updateByUid("PL-UID-5", new UpdatePriceListRequest("Wholesale",
                null, null, null, null, true, null));

        assertThat(target.isDefault()).isTrue();
        assertThat(target.getCurrency()).isEqualTo(CurrencyCode.of("USD"));
        assertThat(target.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void update_supplyingCurrencyAndDates_stillOverwritesThem() {
        PriceList target = activeList("RETAIL", "Retail");
        target.setCurrency(CurrencyCode.of("TZS"));
        target.setEffectiveFrom(LocalDate.of(2026, 1, 1));
        target.setEffectiveTo(LocalDate.of(2026, 12, 31));
        when(priceLists.findByUid("PL-UID-6")).thenReturn(Optional.of(target));

        service.updateByUid("PL-UID-6", new UpdatePriceListRequest("Retail",
                "KES", LocalDate.of(2027, 2, 1), LocalDate.of(2027, 11, 30), null, null, null));

        assertThat(target.getCurrency()).isEqualTo(CurrencyCode.of("KES"));
        assertThat(target.getEffectiveFrom()).isEqualTo(LocalDate.of(2027, 2, 1));
        assertThat(target.getEffectiveTo()).isEqualTo(LocalDate.of(2027, 11, 30));
    }

    // -------------------------------------------------------------------------
    // Archiving surrenders the default flag
    // -------------------------------------------------------------------------
    // Price resolution only looks at ACTIVE lists, so an archived row still holding the flag is a
    // default that answers nothing: blank selling prices on the reports, and a Default badge on the
    // archived row.
    // -------------------------------------------------------------------------

    @Test
    void archive_clearsTheDefaultFlag() {
        PriceList target = activeList("RETAIL", "Retail");
        target.setDefault(true);
        when(priceLists.findByUid("PL-UID-7")).thenReturn(Optional.of(target));

        service.archiveByUid("PL-UID-7");

        assertThat(target.getStatus()).isEqualTo(MasterStatus.ARCHIVED);
        assertThat(target.isDefault()).isFalse();
    }

    // -------------------------------------------------------------------------

    private static PriceList activeList(String code, String name) {
        return new PriceList(COMPANY_ID, code, name, 1L);
    }

    /**
     * A sibling list that currently holds the flag. Mocked rather than constructed so it can carry an
     * id — a real unsaved entity has none, and the "is this the same row?" comparison is the point.
     */
    private static PriceList flaggedOtherList() {
        PriceList other = mock(PriceList.class);
        when(other.getId()).thenReturn(99L);
        return other;
    }

    private PriceList captureSavedPriceList(CreatePriceListRequest req) {
        service.create(req);
        org.mockito.ArgumentCaptor<PriceList> captor = org.mockito.ArgumentCaptor.forClass(PriceList.class);
        verify(priceLists).save(captor.capture());
        return captor.getValue();
    }
}
