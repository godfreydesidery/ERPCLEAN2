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
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
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

    private PriceList captureSavedPriceList(CreatePriceListRequest req) {
        service.create(req);
        org.mockito.ArgumentCaptor<PriceList> captor = org.mockito.ArgumentCaptor.forClass(PriceList.class);
        verify(priceLists).save(captor.capture());
        return captor.getValue();
    }
}
