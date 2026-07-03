package com.erp.modules.products.service;

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
}
