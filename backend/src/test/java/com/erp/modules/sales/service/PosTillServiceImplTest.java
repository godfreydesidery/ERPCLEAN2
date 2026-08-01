package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.entity.PosSession;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.repository.PosSessionRepository;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PosTillServiceImpl}.
 *
 * <p>Regression for ISSUE-008: createTill must persist and return a PosTillDto even when
 * no code is supplied. Before V82, {@code pos_tills.code} was NOT NULL with no generator,
 * so every INSERT failed with a constraint violation → 500.
 *
 * <p>Regression for fix/e2e-pos2b: createTill must resolve and set cash_bank_account_id.
 * Before this fix, the entity field and constructor param were absent, causing a NOT NULL
 * constraint violation on {@code pos_tills.cash_bank_account_id}.
 */
class PosTillServiceImplTest {

    private PosTillRepository          tills;
    private CompanyRepository          companies;
    private CashBankAccountRepository  cashAccounts;
    private PosSessionRepository       sessions;
    private UserLookupService          userLookup;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private SalesDepthNumberGenerator  numberGen;
    private PosTillServiceImpl         service;

    @BeforeEach
    void setUp() {
        tills        = mock(PosTillRepository.class);
        companies    = mock(CompanyRepository.class);
        cashAccounts = mock(CashBankAccountRepository.class);
        sessions     = mock(PosSessionRepository.class);
        userLookup   = mock(UserLookupService.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);
        numberGen    = mock(SalesDepthNumberGenerator.class);
        when(numberGen.nextPosTill(anyLong())).thenReturn("TILL-0001");
        when(userLookup.displayNamesByIds(anyCollection())).thenReturn(Map.of());
        service      = new PosTillServiceImpl(tills, companies, cashAccounts, sessions, userLookup,
                scopeGuard, audit, numberGen);

        RequestContext.set(new RequestContext.Principal(1L, "operator", false, 10L, 20L, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /**
     * ISSUE-008 regression + fix/e2e-pos2b regression:
     * When no cashBankAccountUid is provided, the service must fall back to the company's
     * default cash account and set cashBankAccountId on the persisted till.
     * The returned DTO must carry cashBankAccountId.
     */
    @Test
    void createTill_noCashAccountUid_usesDefaultAccount_persistsAndReturnsDto() {
        // Arrange — company
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("CMP-001")).thenReturn(Optional.of(company));

        // Default cash account stub
        CashBankAccount defaultAccount = mock(CashBankAccount.class);
        when(defaultAccount.getId()).thenReturn(55L);
        when(cashAccounts.findByCompanyIdAndIsDefaultTrue(10L))
                .thenReturn(Optional.of(defaultAccount));

        // The saved entity must have id/uid assigned (simulate what the DB would do)
        PosTill saved = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(saved, 42L, "01HXYZ0000000000000000TEST");
        when(tills.save(any(PosTill.class))).thenReturn(saved);

        CreatePosTillRequest req = new CreatePosTillRequest("CMP-001", 20L, "Till 1", null);

        // Act
        PosTillDto dto = service.createTill(req);

        // Assert
        assertThat(dto.id()).isEqualTo(42L);
        assertThat(dto.uid()).isEqualTo("01HXYZ0000000000000000TEST");
        assertThat(dto.companyId()).isEqualTo(10L);
        assertThat(dto.branchId()).isEqualTo(20L);
        assertThat(dto.name()).isEqualTo("Till 1");
        assertThat(dto.cashBankAccountId()).isEqualTo(55L);
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);

        verify(tills).save(any(PosTill.class));
        verify(audit).record(any());
    }

    /**
     * When cashBankAccountUid is explicitly provided, the service must resolve it by
     * (companyId, uid) and use that account's id.
     */
    @Test
    void createTill_withExplicitCashAccountUid_resolvesAndPersists() {
        // Arrange — company
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("CMP-001")).thenReturn(Optional.of(company));

        // Explicit cash account stub
        CashBankAccount explicitAccount = mock(CashBankAccount.class);
        when(explicitAccount.getId()).thenReturn(77L);
        when(cashAccounts.findByCompanyIdAndUid(10L, "CASH-UID-001"))
                .thenReturn(Optional.of(explicitAccount));

        PosTill saved = new PosTill(10L, 20L, "Till 2", 77L, 1L);
        setIdAndUid(saved, 43L, "01HXYZ0000000000000000T002");
        when(tills.save(any(PosTill.class))).thenReturn(saved);

        CreatePosTillRequest req = new CreatePosTillRequest("CMP-001", 20L, "Till 2", "CASH-UID-001");

        // Act
        PosTillDto dto = service.createTill(req);

        // Assert
        assertThat(dto.cashBankAccountId()).isEqualTo(77L);
        assertThat(dto.name()).isEqualTo("Till 2");

        verify(tills).save(any(PosTill.class));
    }

    // -------------------------------------------------------------------------
    // FIX 3 (busy-day sim, minor): pos_tills.code must never be left null.
    // -------------------------------------------------------------------------

    @Test
    void createTill_generatesNonNullCode_fromSalesDepthNumberGenerator() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(10L);
        when(companies.findByUid("CMP-001")).thenReturn(Optional.of(company));

        CashBankAccount defaultAccount = mock(CashBankAccount.class);
        when(defaultAccount.getId()).thenReturn(55L);
        when(cashAccounts.findByCompanyIdAndIsDefaultTrue(10L))
                .thenReturn(Optional.of(defaultAccount));

        // Echo back whatever the service passed to save() so we can inspect the code it set.
        when(tills.save(any(PosTill.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatePosTillRequest req = new CreatePosTillRequest("CMP-001", 20L, "Till 3", null);

        PosTillDto dto = service.createTill(req);

        assertThat(dto.code())
                .as("pos_tills.code must be generated, never left null (busy-day-sim bugfix)")
                .isEqualTo("TILL-0001");
        verify(numberGen).nextPosTill(10L);
    }

    // -------------------------------------------------------------------------
    // FIX 4 (busy-day sim, minor): the till list must expose whether a till is occupied.
    // -------------------------------------------------------------------------

    @Test
    void getTillByUid_openSessionExists_hasOpenSessionTrue() {
        PosTill till = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(till, 42L, "01HXYZ0000000000000000TEST");
        when(tills.findByUid("01HXYZ0000000000000000TEST")).thenReturn(Optional.of(till));

        PosSession openSession = mock(PosSession.class);
        when(sessions.findByPosTillIdAndStatus(42L, PosSessionStatus.OPEN))
                .thenReturn(Optional.of(openSession));

        PosTillDto dto = service.getTillByUid("01HXYZ0000000000000000TEST");

        assertThat(dto.hasOpenSession())
                .as("a till with an OPEN session must report hasOpenSession=true")
                .isTrue();
    }

    @Test
    void getTillByUid_noOpenSession_hasOpenSessionFalse() {
        PosTill till = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(till, 44L, "01HXYZ0000000000000000FREE");
        when(tills.findByUid("01HXYZ0000000000000000FREE")).thenReturn(Optional.of(till));
        // sessions.findByPosTillIdAndStatus is unstubbed → Mockito default Optional.empty()

        PosTillDto dto = service.getTillByUid("01HXYZ0000000000000000FREE");

        assertThat(dto.hasOpenSession())
                .as("a free till must report hasOpenSession=false")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Naming the occupant of a busy till (orphaned-shift recovery, 2026-08).
    // -------------------------------------------------------------------------

    @Test
    void getTillByUid_openSession_namesTheCashier() {
        PosTill till = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(till, 42L, "01HXYZ0000000000000000TEST");
        when(tills.findByUid("01HXYZ0000000000000000TEST")).thenReturn(Optional.of(till));

        PosSession openSession = mock(PosSession.class);
        when(openSession.getCashierId()).thenReturn(7L);
        when(sessions.findByPosTillIdAndStatus(42L, PosSessionStatus.OPEN))
                .thenReturn(Optional.of(openSession));
        when(userLookup.displayNamesByIds(anyCollection()))
                .thenReturn(Map.of(7L, "Asha Mwakalinga"));

        PosTillDto dto = service.getTillByUid("01HXYZ0000000000000000TEST");

        assertThat(dto.openSessionCashierName())
                .as("an occupied till must name its holder, not just carry the id")
                .isEqualTo("Asha Mwakalinga");
    }

    /**
     * A cashier whose account has since been removed must still produce friendly copy — never a
     * null the till app has to paper over, and never an internal id.
     */
    @Test
    void getTillByUid_cashierNoLongerResolvable_fallsBackToNeutralPhrase() {
        PosTill till = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(till, 42L, "01HXYZ0000000000000000TEST");
        when(tills.findByUid("01HXYZ0000000000000000TEST")).thenReturn(Optional.of(till));

        PosSession openSession = mock(PosSession.class);
        when(openSession.getCashierId()).thenReturn(7L);
        when(sessions.findByPosTillIdAndStatus(42L, PosSessionStatus.OPEN))
                .thenReturn(Optional.of(openSession));
        // userLookup returns an empty map (default stub) — the id resolved to nothing.

        PosTillDto dto = service.getTillByUid("01HXYZ0000000000000000TEST");

        assertThat(dto.openSessionCashierName()).isEqualTo("Another cashier");
        assertThat(dto.openSessionCashierName()).doesNotContain("7");
    }

    @Test
    void getTillByUid_noOpenSession_hasNoCashierName() {
        PosTill till = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(till, 44L, "01HXYZ0000000000000000FREE");
        when(tills.findByUid("01HXYZ0000000000000000FREE")).thenReturn(Optional.of(till));

        PosTillDto dto = service.getTillByUid("01HXYZ0000000000000000FREE");

        assertThat(dto.openSessionCashierName())
                .as("a free till has no occupant to name")
                .isNull();
    }

    /**
     * The names for a whole branch must be resolved in ONE lookup. Mapping row by row would ask
     * IAM per till — an N+1 that grows with the branch, and the reason toDto takes a name map.
     */
    @Test
    void listTillsByBranch_resolvesAllCashierNamesInOneBatchLookup() {
        PosTill busy1 = new PosTill(10L, 20L, "Till 1", 55L, 1L);
        setIdAndUid(busy1, 1L, "01HXYZ00000000000000000001");
        PosTill busy2 = new PosTill(10L, 20L, "Till 2", 55L, 1L);
        setIdAndUid(busy2, 2L, "01HXYZ00000000000000000002");
        PosTill free = new PosTill(10L, 20L, "Till 3", 55L, 1L);
        setIdAndUid(free, 3L, "01HXYZ00000000000000000003");
        when(tills.findByCompanyIdAndBranchId(10L, 20L))
                .thenReturn(List.of(busy1, busy2, free));

        PosSession s1 = mock(PosSession.class);
        when(s1.getCashierId()).thenReturn(7L);
        PosSession s2 = mock(PosSession.class);
        when(s2.getCashierId()).thenReturn(8L);
        when(sessions.findByPosTillIdAndStatus(1L, PosSessionStatus.OPEN))
                .thenReturn(Optional.of(s1));
        when(sessions.findByPosTillIdAndStatus(2L, PosSessionStatus.OPEN))
                .thenReturn(Optional.of(s2));
        // till 3 unstubbed → Optional.empty()

        when(userLookup.displayNamesByIds(anyCollection()))
                .thenReturn(Map.of(7L, "Asha Mwakalinga", 8L, "Baraka Juma"));

        List<PosTillDto> dtos = service.listTillsByBranch(10L, 20L);

        assertThat(dtos).extracting(PosTillDto::openSessionCashierName)
                .containsExactly("Asha Mwakalinga", "Baraka Juma", null);
        verify(userLookup, times(1)).displayNamesByIds(anyCollection());
        verify(userLookup).displayNamesByIds(Set.of(7L, 8L));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void setIdAndUid(PosTill entity, Long id, String uid) {
        try {
            var idField = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);

            var uidMethod = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            uidMethod.setAccessible(true);
            uidMethod.invoke(entity, uid);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Test helper setIdAndUid failed", e);
        }
    }
}
