package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.BarcodeSymbologyRuleDto;
import com.erp.modules.products.domain.dto.CreateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.dto.EmbeddedBarcodeDecode;
import com.erp.modules.products.domain.entity.BarcodeSymbologyRule;
import com.erp.modules.products.domain.enums.SymbologyItemMatch;
import com.erp.modules.products.domain.enums.SymbologyValueKind;
import com.erp.modules.products.repository.BarcodeSymbologyRuleRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BarcodeSymbologyRuleServiceImpl} — focused on the decode algorithm
 * (prefix match, offset extraction, value scaling, priority precedence) and CRUD guards
 * (ADR-0044 D-1a / BR-9).
 */
class BarcodeSymbologyRuleServiceImplTest {

    private BarcodeSymbologyRuleRepository ruleRepo;
    private CompanyRepository companies;
    private ScopeGuard scopeGuard;
    private AuditService audit;
    private BarcodeSymbologyRuleServiceImpl service;

    private static final Long COMPANY_ID = 1L;

    @BeforeEach
    void setUp() {
        ruleRepo   = mock(BarcodeSymbologyRuleRepository.class);
        companies  = mock(CompanyRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        service    = new BarcodeSymbologyRuleServiceImpl(ruleRepo, companies, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(1L, "tester", true, COMPANY_ID, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // decode — prefix match + offset extraction + value scaling
    // -------------------------------------------------------------------------

    @Test
    void decode_prefixMatch_extractsItemCodeAndScalesValue() {
        // EAN-13 type-2 example: prefix "21", item code at [2,7) → "01234", value at [7,12) → "01500"
        // value_decimals = 3 → 1500 / 10^3 = 1.500
        BarcodeSymbologyRule rule = rule("21",
                (short) 2, (short) 5,   // itemCodeStart=2, itemCodeLength=5
                (short) 7, (short) 5,   // valueStart=7, valueLength=5
                (short) 3,              // valueDecimals=3
                SymbologyValueKind.WEIGHT);

        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(rule));

        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "2101234015009");

        assertThat(result).isPresent();
        assertThat(result.get().itemCode()).isEqualTo("01234");
        assertThat(result.get().valueKind()).isEqualTo(SymbologyValueKind.WEIGHT);
        assertThat(result.get().value()).isEqualByComparingTo(new BigDecimal("1.500"));
    }

    @Test
    void decode_priceRule_returnsCorrectValueKind() {
        // prefix "02", value at [7,12) = "01000", value_decimals=2 → 10.00
        BarcodeSymbologyRule rule = rule("02",
                (short) 2, (short) 5,
                (short) 7, (short) 5,
                (short) 2,
                SymbologyValueKind.PRICE);

        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(rule));

        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "0298765010009");

        assertThat(result).isPresent();
        assertThat(result.get().valueKind()).isEqualTo(SymbologyValueKind.PRICE);
        assertThat(result.get().value()).isEqualByComparingTo(new BigDecimal("10.00"));
    }

    @Test
    void decode_priorityPrecedence_firstMatchWins() {
        // Two rules with same prefix — lower priority int value wins.
        BarcodeSymbologyRule highPriority = ruleWithPriority("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 3,
                SymbologyValueKind.WEIGHT, 10);
        BarcodeSymbologyRule lowPriority = ruleWithPriority("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 2,
                SymbologyValueKind.PRICE, 100);

        // Repository returns ordered by priority ASC — high priority (10) first
        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(highPriority, lowPriority));

        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "2101234015009");

        assertThat(result).isPresent();
        // The rule with priority=10 (WEIGHT, decimals=3) must win
        assertThat(result.get().valueKind()).isEqualTo(SymbologyValueKind.WEIGHT);
        assertThat(result.get().value()).isEqualByComparingTo(new BigDecimal("1.500"));
    }

    @Test
    void decode_noMatchingPrefix_returnsEmpty() {
        BarcodeSymbologyRule rule = rule("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 3,
                SymbologyValueKind.WEIGHT);

        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(rule));

        // barcode starts with "99" — does not match prefix "21"
        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "9901234015009");

        assertThat(result).isEmpty();
    }

    @Test
    void decode_barcodeTooShort_skipsRuleAndReturnsEmpty() {
        // itemCodeEnd = 2+5 = 7, valueEnd = 7+5 = 12; barcode length 8 < 12
        BarcodeSymbologyRule rule = rule("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 3,
                SymbologyValueKind.WEIGHT);

        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(rule));

        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "21012340");

        assertThat(result).isEmpty();
    }

    @Test
    void decode_nullBarcode_returnsEmpty() {
        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, null);
        assertThat(result).isEmpty();
    }

    @Test
    void decode_emptyBarcode_returnsEmpty() {
        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "");
        assertThat(result).isEmpty();
    }

    @Test
    void decode_nonNumericValueField_skipsRuleAndReturnsEmpty() {
        // value substring "ABCDE" cannot be parsed as integer → rule skipped
        BarcodeSymbologyRule rule = rule("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 3,
                SymbologyValueKind.WEIGHT);

        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(rule));

        // "21" + "01234" + "ABCDE" + "X" = 13 chars; value substring is "ABCDE"
        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "2101234ABCDEX");

        assertThat(result).isEmpty();
    }

    @Test
    void decode_valueDecimals0_returnsIntegerValue() {
        // prefix "21" (2 chars) + item "01234" (5 chars, [2,7)) + value "01500" (5 chars, [7,12)) + "9" = 13 chars
        // value_decimals=0 → 1500 (no scaling)
        BarcodeSymbologyRule rule = rule("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 0,
                SymbologyValueKind.WEIGHT);

        when(ruleRepo.findByCompanyIdAndStatusOrderByPriorityAsc(COMPANY_ID, MasterStatus.ACTIVE))
                .thenReturn(List.of(rule));

        // [7,12) on "2101234015009" = "01500" → rawInt=1500; decimals=0 → 1500
        Optional<EmbeddedBarcodeDecode> result = service.decode(COMPANY_ID, "2101234015009");

        assertThat(result).isPresent();
        assertThat(result.get().value()).isEqualByComparingTo(new BigDecimal("1500"));
    }

    // -------------------------------------------------------------------------
    // CRUD — create conflict guard
    // -------------------------------------------------------------------------

    @Test
    void create_duplicateCode_throwsConflict() {
        var company = mockCompany();
        when(companies.findByUid("C1")).thenReturn(Optional.of(company));
        when(ruleRepo.findByCompanyIdAndCode(COMPANY_ID, "EAN13-W"))
                .thenReturn(Optional.of(mock(BarcodeSymbologyRule.class)));

        var req = new CreateBarcodeSymbologyRuleRequest("C1", "EAN13-W", "EAN-13 Weight",
                "21", SymbologyValueKind.WEIGHT, (short) 2, (short) 5, (short) 7, (short) 5, (short) 3);

        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("EAN13-W");
    }

    @Test
    void create_validRequest_savesAndReturnsDto() {
        var company = mockCompany();
        when(companies.findByUid("C1")).thenReturn(Optional.of(company));
        when(ruleRepo.findByCompanyIdAndCode(COMPANY_ID, "EAN13-W")).thenReturn(Optional.empty());

        BarcodeSymbologyRule saved = rule("21",
                (short) 2, (short) 5, (short) 7, (short) 5, (short) 3,
                SymbologyValueKind.WEIGHT);
        // Simulate the saved entity having uid/id set (UidEntity assigns on persist; mock it here)
        when(ruleRepo.save(any())).thenReturn(saved);

        var req = new CreateBarcodeSymbologyRuleRequest("C1", "EAN13-W", "EAN-13 Weight",
                "21", SymbologyValueKind.WEIGHT, (short) 2, (short) 5, (short) 7, (short) 5, (short) 3);

        BarcodeSymbologyRuleDto dto = service.create(req);

        verify(ruleRepo).save(any(BarcodeSymbologyRule.class));
        assertThat(dto.prefix()).isEqualTo("21");
        assertThat(dto.valueKind()).isEqualTo(SymbologyValueKind.WEIGHT);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private BarcodeSymbologyRule rule(String prefix,
                                      short itemStart, short itemLen,
                                      short valStart, short valLen,
                                      short valDecimals,
                                      SymbologyValueKind kind) {
        return ruleWithPriority(prefix, itemStart, itemLen, valStart, valLen,
                valDecimals, kind, 100);
    }

    private BarcodeSymbologyRule ruleWithPriority(String prefix,
                                                   short itemStart, short itemLen,
                                                   short valStart, short valLen,
                                                   short valDecimals,
                                                   SymbologyValueKind kind,
                                                   int priority) {
        return new BarcodeSymbologyRule(COMPANY_ID, "CODE", "Test Rule",
                prefix, kind,
                itemStart, itemLen,
                valStart, valLen,
                valDecimals,
                SymbologyItemMatch.BARCODE,
                priority, null);
    }

    private com.erp.modules.iam.domain.entity.Company mockCompany() {
        var company = mock(com.erp.modules.iam.domain.entity.Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        return company;
    }
}
