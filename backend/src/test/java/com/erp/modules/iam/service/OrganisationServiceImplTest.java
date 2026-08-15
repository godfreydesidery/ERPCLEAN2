package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.CreateTenantRequest;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.bootstrap.TenantProvisioningService.NewTenantRequest;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit test for {@code OrganisationServiceImpl.createTenant} — the normalising boundary between the
 * HTTP request and {@link NewTenantRequest} (ADR-0062 P5-2).
 *
 * <p>Its sibling {@code OrganisationServiceImplIT} runs under failsafe and is not in the PR gate, so
 * until this class existed the mapping that decides what a new tenant is made of had no gate cover
 * at all. The two things it can get wrong are invisible to the compiler: a transposed pair of
 * same-typed String arguments, and a fallback applied where the design says "create nothing".
 */
@ExtendWith(MockitoExtension.class)
class OrganisationServiceImplTest {

    @Mock OrganisationRepository organisations;
    @Mock TenantProvisioningService provisioning;
    @Mock AuditService audit;

    private OrganisationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrganisationServiceImpl(organisations, provisioning, audit);
        when(provisioning.provision(any())).thenReturn(
                new TenantProvisioningService.ProvisionedTenant(1L, 2L, 3L, 4L,
                        "Kilimanjaro Group", "KS", "HQ", "orgadmin@kilimanjaro"));
        when(organisations.findScopedById(1L))
                .thenReturn(Optional.of(new Organisation("Kilimanjaro Group")));
    }

    /**
     * Distinct sentinel values per field on purpose: identical placeholders would pass while two
     * String components were transposed, which is the one mapping error the compiler cannot see and
     * bootstrap cannot expose (a run of nulls is order-insensitive).
     */
    @Test
    @DisplayName("every new field reaches its matching component, verbatim")
    void newFieldsAreMappedOneForOne() {
        service.createTenant(request("legal-1", "tin-2", "vrn-3",
                "code-4", "name-5", "walkin-6", "till-7"));

        NewTenantRequest mapped = captured();
        assertThat(mapped.companyLegalName()).isEqualTo("legal-1");
        assertThat(mapped.companyTaxId()).isEqualTo("tin-2");
        assertThat(mapped.companyVrn()).isEqualTo("vrn-3");
        assertThat(mapped.priceListCode()).isEqualTo("code-4");
        assertThat(mapped.priceListName()).isEqualTo("name-5");
        assertThat(mapped.walkInCustomerName()).isEqualTo("walkin-6");
        assertThat(mapped.posTillName()).isEqualTo("till-7");
    }

    @Test
    @DisplayName("surrounding whitespace is removed once, at the boundary")
    void valuesAreStrippedAtTheBoundary() {
        service.createTenant(request("  Kilimanjaro Ltd  ", " 123-456-789 ", " 40-1-A ",
                " RETAIL ", " Retail ", " Walk-in Customer ", "  HQ Till 1  "));

        NewTenantRequest mapped = captured();
        assertThat(mapped.companyLegalName()).isEqualTo("Kilimanjaro Ltd");
        assertThat(mapped.companyTaxId()).isEqualTo("123-456-789");
        // Load-bearing, not cosmetic: uq_pos_till_company_name is a plain UNIQUE with no btrim, so
        // a pasted leading space makes " HQ Till 1" a permanently different till from "HQ Till 1".
        assertThat(mapped.posTillName()).isEqualTo("HQ Till 1");
        assertThat(mapped.priceListName()).isEqualTo("Retail");
    }

    @Test
    @DisplayName("absent, empty and whitespace-only all arrive as null — nothing is substituted")
    void blankNewFieldsArriveAsNull() {
        service.createTenant(request(null, "", "   ", null, "", "   ", null));

        NewTenantRequest mapped = captured();
        assertThat(mapped.companyLegalName()).isNull();
        assertThat(mapped.companyTaxId()).isNull();
        assertThat(mapped.companyVrn()).isNull();
        assertThat(mapped.priceListCode()).isNull();
        assertThat(mapped.priceListName()).isNull();
        assertThat(mapped.walkInCustomerName()).isNull();
        assertThat(mapped.posTillName()).isNull();
    }

    /**
     * The VAT stance is the one new field that is not a String, so a transposition cannot hide in it.
     * What can is the reflex to normalise the null away: null means "the operator did not state a
     * stance", and only {@code DefaultPriceListProvisioner} may decide what that means. Substituting
     * false here would hard-code VAT-EXCLUSIVE at the boundary, out of sight of the one place that
     * documents the choice — and an exclusive list holding VAT-inclusive shelf prices adds 18% to
     * every line sold.
     */
    @Test
    @DisplayName("an unstated VAT stance crosses the boundary as null, not as false")
    void anUnstatedVatStanceStaysNull() {
        service.createTenant(request(null, null, null, "RETAIL", "Retail", null, null, null));

        assertThat(captured().priceListIncludesVat()).isNull();
    }

    @Test
    @DisplayName("a stated VAT stance is passed through unchanged")
    void aStatedVatStanceIsPassedThrough() {
        service.createTenant(request(null, null, null, "RETAIL", "Retail", null, null, true));

        assertThat(captured().priceListIncludesVat()).isTrue();
    }

    /**
     * The new one-argument {@code blankToNull} sits beside a two-argument helper that is really
     * blank-to-FALLBACK. Confusing them would either give a tenant an invented TIN or strip the
     * currency default that forty read paths depend on, so both halves are pinned here.
     */
    @Test
    @DisplayName("the existing fallbacks still apply to time zone, display name and currency")
    void existingFallbacksAreUnchanged() {
        service.createTenant(new CreateTenantRequest(
                "Kilimanjaro Group", "  ", "KS", "Kilimanjaro Supermarket",
                null, null, null,
                "HQ", "Head Office", "orgadmin", "OrgPass12345", "   ",
                "  ", "", List.of(),
                null, null, null, null, null));

        NewTenantRequest mapped = captured();
        assertThat(mapped.timeZone()).isEqualTo("Africa/Dar_es_Salaam");
        assertThat(mapped.adminDisplayName()).isEqualTo("orgadmin");
        assertThat(mapped.baseCurrency()).isEqualTo("TZS");
        assertThat(mapped.defaultCurrency()).isEqualTo("TZS");
        assertThat(mapped.enabledCurrencies()).containsExactly("TZS");
        // API-provisioned tenants compose the administrator's username (D-7); bootstrap does not.
        assertThat(mapped.composeAdminUsername()).isTrue();
    }

    // -------------------------------------------------------------------------

    private NewTenantRequest captured() {
        ArgumentCaptor<NewTenantRequest> captor = ArgumentCaptor.forClass(NewTenantRequest.class);
        verify(provisioning).provision(captor.capture());
        return captor.getValue();
    }

    private static CreateTenantRequest request(String legalName, String taxId, String vrn,
                                               String priceListCode, String priceListName,
                                               String walkInName, String tillName) {
        return request(legalName, taxId, vrn, priceListCode, priceListName, walkInName, tillName,
                null);
    }

    private static CreateTenantRequest request(String legalName, String taxId, String vrn,
                                               String priceListCode, String priceListName,
                                               String walkInName, String tillName,
                                               Boolean priceListIncludesVat) {
        return new CreateTenantRequest(
                "Kilimanjaro Group", "Africa/Dar_es_Salaam",
                "KS", "Kilimanjaro Supermarket",
                legalName, taxId, vrn,
                "HQ", "Head Office",
                "orgadmin", "OrgPass12345", "Org Admin",
                "TZS", "TZS", List.of("TZS"),
                priceListCode, priceListName, priceListIncludesVat, walkInName, tillName);
    }
}
