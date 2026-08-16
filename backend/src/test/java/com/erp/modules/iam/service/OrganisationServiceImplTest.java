package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.CreateTenantRequest;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.bootstrap.TenantProvisioningService.NewTenantRequest;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
        // lenient: the setStatus cases below never reach createTenant, and strict stubs would fail
        // them for a stub they were never meant to use.
        lenient().when(provisioning.provision(any())).thenReturn(
                new TenantProvisioningService.ProvisionedTenant(1L, 2L, 3L, 4L,
                        "Kilimanjaro Group", "KS", "HQ", "orgadmin@kilimanjaro"));
        lenient().when(organisations.findScopedById(1L))
                .thenReturn(Optional.of(new Organisation("Kilimanjaro Group")));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
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
        // ...and their administrator is a CUSTOMER's administrator, never the vendor's platform
        // operator. Root short-circuits PermissionResolver ahead of every permission code, so a
        // root tenant administrator effectively holds ORG.CREATE and ORG.SUSPEND — over every other
        // customer's organisation — whatever the seed withholds from ORG_ADMIN.
        assertThat(mapped.platformRootAdmin()).isFalse();
    }

    // -------------------------------------------------------------------------
    // setStatus — the decision table
    // -------------------------------------------------------------------------
    //
    // Changing an organisation's status is a PLATFORM-tier action, not a tenant-scoped one. Over
    // (is the target my own organisation?, am I a platform operator?) the rule is:
    //
    //   own + suspend                  -> refuse (409). Suspension is enforced at login and resume
    //                                     requires being signed in, so this is a self-inflicted brick.
    //   own + resume                   -> allow. The recovery path.
    //   foreign + platform operator     -> allow. This is the entire purpose of the endpoint; a naive
    //                                     same-tenant predicate would break the vendor.
    //   foreign + not platform operator -> refuse, shaped as 404 rather than 403.
    //
    // The last row is the one that had no code behind it: the target was loaded with a bare
    // findByUid and the only guard refused suspending your OWN organisation, so every OTHER
    // customer's organisation was reachable.

    @Test
    @DisplayName("a tenant caller cannot suspend another tenant — and is told nothing exists")
    void anotherTenantsOrganisationIsNotSuspendable() {
        Organisation target = organisation(99L, "Another Customer");
        when(organisations.findByUid("other-uid")).thenReturn(Optional.of(target));
        actingAs(7L, false);

        assertThatThrownBy(() -> service.setStatus("other-uid", false))
                .as("this is the reported measurement: tenant B's administrator suspending tenant A")
                .isInstanceOf(NotFoundException.class)
                // NOT Forbidden: "it exists but is not yours" is an existence oracle over a uid an
                // outsider might plausibly hold. The refusal must be indistinguishable from a uid
                // that names nothing, so the message may not mention the organisation either.
                .hasMessageNotContainingAny("Another Customer", "other-uid", "permission", "tenant");

        assertThat(target.getStatus()).isEqualTo(MasterStatus.ACTIVE);
        verify(organisations, never()).save(any(Organisation.class));
    }

    @Test
    @DisplayName("a tenant caller cannot RESUME another tenant either")
    void anotherTenantsOrganisationIsNotResumable() {
        // Resume was the unguarded half: the pre-existing check began `if (!active ...)`, so it did
        // not look at the target at all on this path.
        when(organisations.findByUid("other-uid")).thenReturn(Optional.of(organisation(99L, "X")));
        actingAs(7L, false);

        assertThatThrownBy(() -> service.setStatus("other-uid", true))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("the platform operator CAN suspend another tenant — that is the endpoint's purpose")
    void thePlatformOperatorMaySuspendAnotherTenant() {
        Organisation target = organisation(99L, "A Customer");
        when(organisations.findByUid("other-uid")).thenReturn(Optional.of(target));
        actingAs(7L, true);

        service.setStatus("other-uid", false);

        assertThat(target.getStatus())
                .as("a same-tenant predicate would have broken exactly the caller this exists for")
                .isEqualTo(MasterStatus.INACTIVE);
    }

    @Test
    @DisplayName("suspending your own organisation is still refused, root or not")
    void suspendingYourOwnOrganisationIsRefused() {
        when(organisations.findByUid("mine")).thenReturn(Optional.of(organisation(7L, "Mine")));
        actingAs(7L, true);

        assertThatThrownBy(() -> service.setStatus("mine", false))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    @DisplayName("resuming your own organisation is allowed — it is the recovery path")
    void resumingYourOwnOrganisationIsAllowed() {
        Organisation mine = organisation(7L, "Mine");
        mine.setStatus(MasterStatus.INACTIVE);
        when(organisations.findByUid("mine")).thenReturn(Optional.of(mine));
        actingAs(7L, false);

        service.setStatus("mine", true);

        assertThat(mine.getStatus()).isEqualTo(MasterStatus.ACTIVE);
    }

    /**
     * The detective control, and the only part of this change that alters what a live deployment
     * records. A suspend row used to name the target and nothing else, so "the vendor suspended a
     * customer" and "one customer's administrator suspended another customer" were the same entry in
     * an append-only log — which is how a cross-tenant suspend could have happened without leaving a
     * trace anyone could find afterwards.
     */
    @Test
    @DisplayName("a cross-tenant suspend is recorded AS cross-tenant, with both organisation ids")
    void theAuditRowNamesBothSidesOfACrossTenantAction() {
        when(organisations.findByUid("other-uid")).thenReturn(Optional.of(organisation(99L, "X")));
        actingAs(7L, true);

        service.setStatus("other-uid", false);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().detail())
                .containsEntry("callerOrganisationId", "7")
                .containsEntry("targetOrganisationId", "99")
                .containsEntry("crossTenant", "true");
    }

    // -------------------------------------------------------------------------

    /** An organisation with an id, which a mock repository never assigns. */
    private static Organisation organisation(Long id, String name) {
        Organisation org = new Organisation(name);
        ReflectionTestUtils.setField(org, "id", id);
        return org;
    }

    private static void actingAs(Long organisationId, boolean root) {
        RequestContext.set(new RequestContext.Principal(
                1L, "caller", root, 10L, 20L, "127.0.0.1", organisationId));
    }

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
