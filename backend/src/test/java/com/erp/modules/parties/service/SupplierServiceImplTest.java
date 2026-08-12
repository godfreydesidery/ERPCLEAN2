package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.repository.SupplierBankAccountRepository;
import com.erp.modules.parties.repository.SupplierBranchRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.tax.repository.WhtTypeRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the supplier registration field guards (UAT wave 1, defect 1).
 *
 * <p>What UAT hit: {@code country} is {@code VARCHAR(2)} (ISO alpha-2), the API accepted
 * {@code "Tanzania"}, and Postgres rejected it at flush. The caller got a 400 reading "The supplied
 * value violates a data constraint. Check field formats, lengths, and numeric ranges." — no field
 * named, no expected form, nothing to act on. The procurement officer only got past it by reading
 * the source; the screen was a dead end.
 *
 * <p>These tests assert the two things that make the message usable: it NAMES the field, and it
 * shows the expected form with a worked example. They also pin the two behaviours that matter
 * around it — a valid code still works (and is normalised), and nothing is persisted on refusal.
 */
class SupplierServiceImplTest {

    private static final Long COMPANY_ID = 10L;

    private SupplierRepository suppliers;
    private PartyCodeGenerator codeGen;
    private SupplierServiceImpl service;

    @BeforeEach
    void setUp() {
        suppliers = mock(SupplierRepository.class);
        codeGen = mock(PartyCodeGenerator.class);

        service = new SupplierServiceImpl(
                suppliers,
                mock(SupplierBranchRepository.class),
                mock(SupplierBankAccountRepository.class),
                mock(PaymentTermsRepository.class),
                mock(WhtTypeRepository.class),
                codeGen,
                mock(PartyBranchGuard.class),
                mock(ScopeGuard.class),
                mock(AuditService.class));

        when(codeGen.next(anyLong(), anyString())).thenReturn("SUPPLIER-0001");
        // save() returns what it was handed — enough for the DTO mapping on the happy path.
        when(suppliers.save(any(Supplier.class))).thenAnswer(inv -> inv.getArgument(0));

        RequestContext.set(new RequestContext.Principal(
                99L, "procurement@test.com", false, COMPANY_ID, 1L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CreateSupplierRequest createWith(String country, String defaultCurrency) {
        return new CreateSupplierRequest(
                COMPANY_ID, PartyType.INDIVIDUAL, "Kilimanjaro Traders",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null,
                country, defaultCurrency, null, null, null);
    }

    private UpdateSupplierRequest updateWith(String country) {
        return new UpdateSupplierRequest(
                PartyType.INDIVIDUAL, "Kilimanjaro Traders",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null,
                country, null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // Defect 1: the country dead-end
    // -----------------------------------------------------------------------

    @Test
    void create_withCountryName_saysWhichFieldAndWhatIsExpected() {
        assertThatThrownBy(() -> service.create(createWith("Tanzania", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Country")            // names the field
                .hasMessageContaining("two-letter")         // states the expected form
                .hasMessageContaining("TZ")                 // gives a worked example
                .hasMessageContaining("Tanzania");          // echoes what was supplied
    }

    /** The old message named no field at all — the whole reason a user could not proceed. */
    @Test
    void create_withCountryName_doesNotFallBackToTheGenericConstraintMessage() {
        assertThatThrownBy(() -> service.create(createWith("Tanzania", null)))
                .hasMessageNotContaining("violates a data constraint")
                .hasMessageNotContaining("numeric ranges");
    }

    @Test
    void create_withCountryName_persistsNothing() {
        assertThatThrownBy(() -> service.create(createWith("Tanzania", null)))
                .isInstanceOf(IllegalArgumentException.class);

        verify(suppliers, never()).save(any(Supplier.class));
    }

    @Test
    void create_withValidIsoCountryCode_succeeds() {
        SupplierDto dto = service.create(createWith("TZ", null));

        assertThat(dto.country()).isEqualTo("TZ");
    }

    /** A lower-case code is a correct answer typed casually — normalise it, don't punish it. */
    @Test
    void create_withLowerCaseIsoCountryCode_isNormalisedNotRejected() {
        SupplierDto dto = service.create(createWith(" tz ", null));

        assertThat(dto.country()).isEqualTo("TZ");
    }

    @Test
    void create_withNoCountry_isStillAllowed() {
        SupplierDto dto = service.create(createWith(null, null));

        assertThat(dto.country()).isNull();
    }

    /** A blank string is "not filled in", not "invalid". */
    @Test
    void create_withBlankCountry_isTreatedAsNotSupplied() {
        SupplierDto dto = service.create(createWith("   ", null));

        assertThat(dto.country()).isNull();
    }

    @Test
    void update_withCountryName_isRefusedTheSameWayAsCreate() {
        Supplier existing = new Supplier(COMPANY_ID, "SUPPLIER-0001", PartyType.INDIVIDUAL,
                "Kilimanjaro Traders", SupplierKind.GOODS, 99L);
        when(suppliers.findByUid("sup-uid-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.updateByUid("sup-uid-1", updateWith("Tanzania")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Country")
                .hasMessageContaining("two-letter");
    }

    // -----------------------------------------------------------------------
    // The sibling coded column: default_currency VARCHAR(3)
    // -----------------------------------------------------------------------

    /**
     * {@code defaultCurrency} had the same shape of trap. It did not produce the generic constraint
     * message — {@code CurrencyCode} rejected it first — but its wording named a Java class
     * ("CurrencyCode must be exactly 3 ASCII letters"), not the field on the form.
     */
    @Test
    void create_withCurrencyName_namesTheFieldNotTheJavaType() {
        assertThatThrownBy(() -> service.create(createWith("TZ", "Tanzanian Shilling")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Default currency")
                .hasMessageContaining("three-letter")
                .hasMessageContaining("TZS")
                .hasMessageNotContaining("CurrencyCode")
                .hasMessageNotContaining("ASCII");
    }

    @Test
    void create_withValidCurrencyCode_succeeds() {
        SupplierDto dto = service.create(createWith("TZ", "tzs"));

        assertThat(dto.defaultCurrency()).isEqualTo("TZS");
    }

    // -----------------------------------------------------------------------
    // The same trap on the plain length-limited columns
    // -----------------------------------------------------------------------

    @Test
    void create_withOverLongDisplayName_namesTheFieldAndTheLimit() {
        CreateSupplierRequest req = new CreateSupplierRequest(
                COMPANY_ID, PartyType.INDIVIDUAL, "x".repeat(201),
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null,
                null, null, null, null, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Supplier name")
                .hasMessageContaining("200")
                .hasMessageNotContaining("violates a data constraint");
    }

    @Test
    void create_withOverLongTin_namesTheFieldAndTheLimit() {
        CreateSupplierRequest req = new CreateSupplierRequest(
                COMPANY_ID, PartyType.BUSINESS, "Kilimanjaro Traders",
                null, "1".repeat(21), null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null,
                null, null, null, null, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIN")
                .hasMessageContaining("20");
    }

    // -----------------------------------------------------------------------
    // Existing business rules must still fire (guards were added, not swapped in)
    // -----------------------------------------------------------------------

    @Test
    void create_businessSupplierWithoutTin_isStillRefused() {
        CreateSupplierRequest req = new CreateSupplierRequest(
                COMPANY_ID, PartyType.BUSINESS, "Kilimanjaro Traders",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null,
                "TZ", null, null, null, null);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tax Identification Number");
    }

    @Test
    void create_withValidPayload_stillPersists() {
        CreateSupplierRequest req = new CreateSupplierRequest(
                COMPANY_ID, PartyType.INDIVIDUAL, "Kilimanjaro Traders",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, 30, null,
                "TZ", "TZS", 7, new BigDecimal("100.00"), null);

        SupplierDto dto = service.create(req);

        verify(suppliers).save(any(Supplier.class));
        assertThat(dto.country()).isEqualTo("TZ");
        assertThat(dto.defaultCurrency()).isEqualTo("TZS");
        assertThat(dto.leadTimeDays()).isEqualTo(7);
    }
}
