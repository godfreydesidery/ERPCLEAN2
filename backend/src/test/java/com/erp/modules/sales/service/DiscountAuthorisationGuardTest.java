package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.sales.domain.dto.DiscountPolicyDto;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import com.erp.modules.sales.domain.enums.DiscountRefusalCode;
import com.erp.modules.sales.domain.exception.DiscountApprovalException;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Unit tests for {@link DiscountAuthorisationGuard} — the manager-authorised discount ceiling (K7).
 *
 * <p>The first two tests are the most important ones in the file: they pin the shipped default.
 * {@link DiscountPolicyProvider} now reads the real {@code sales_settings} columns (V95), whose
 * column default is OFF — so an existing company, a newly seeded one and a company with no settings
 * row at all all read OFF, and NOTHING changes for any tenant that has not deliberately opted in.
 * Every other test drives the guard through a stubbed provider to exercise the enforcement path a
 * company gets once it does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DiscountAuthorisationGuardTest {

    @Mock DiscountPolicyProvider policies;
    @Mock AppUserRepository users;
    @Mock PermissionResolver permissionResolver;
    @Mock AuditService audit;

    @InjectMocks DiscountAuthorisationGuard guard;

    // Chosen so no id is a substring of the amounts/percentages in the messages — otherwise the
    // "leaks nothing internal" assertion passes or fails for the wrong reason.
    private static final Long   COMPANY_ID  = 4242L;
    private static final Long   OTHER_COMPANY = 4343L;
    private static final Long   BRANCH_ID   = 4444L;
    private static final Long   INVOICE_ID  = 9876L;
    private static final String INVOICE_UID = "INVUID000000000000000000001";
    private static final Long   CASHIER_ID  = 55L;
    private static final Long   MANAGER_ID  = 77L;
    private static final String MANAGER_UID = "MGRUID000000000000000000001";

    @BeforeEach
    void setCaller() {
        RequestContext.set(new RequestContext.Principal(
                CASHIER_ID, "cashier", false, COMPANY_ID, BRANCH_ID, "127.0.0.1"));
    }

    @AfterEach
    void clearCaller() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // The shipped default: OFF for everyone, so no tenant's behaviour changes.
    // -------------------------------------------------------------------------

    @Test
    void aFreshlySeededCompanyReadsOff() {
        // The seeder saves a bare SalesSettings row and relies on the entity's own field
        // initialisers for the defaults (SalesSettingsSeederImpl says so explicitly). If that
        // initialiser were ever anything but OFF, every newly provisioned company would start
        // enforcing a ceiling nobody configured.
        SalesSettingsRepository settingsRepo = org.mockito.Mockito.mock(SalesSettingsRepository.class);
        when(settingsRepo.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(new SalesSettings(COMPANY_ID, null)));

        DiscountPolicyDto seeded = new DiscountPolicyProvider(settingsRepo).policyFor(COMPANY_ID);

        assertThat(seeded.isOff()).isTrue();
        assertThat(seeded.action()).isEqualTo(DiscountApprovalAction.OFF);
        assertThat(seeded.maxDiscountPercent()).isNull();
    }

    @Test
    void aCompanyWithNoSettingsRowReadsOff() {
        // Must agree with what the Sales Settings API reports for the same state — the screen and
        // the guard disagreeing about a missing row is how the negative-stock toggle shipped broken.
        // DiscountPolicySettingCrossLayerContractTest pins that agreement across both layers.
        SalesSettingsRepository settingsRepo = org.mockito.Mockito.mock(SalesSettingsRepository.class);
        when(settingsRepo.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        DiscountPolicyDto unconfigured = new DiscountPolicyProvider(settingsRepo).policyFor(COMPANY_ID);

        assertThat(unconfigured.isOff()).isTrue();
        assertThat(unconfigured.maxDiscountPercent()).isNull();
    }

    @Test
    void aConfiguredCompanyReadsBackExactlyWhatWasStored() {
        // The connection the seam used to break: a stored stance and ceiling must reach the guard
        // unchanged, scale included — a 7.50% ceiling read back as 7 or 8 would silently move the
        // line between "cashier may" and "needs a manager".
        SalesSettings stored = new SalesSettings(COMPANY_ID, null);
        stored.setDiscountApprovalAction(DiscountApprovalAction.APPROVE);
        stored.setMaxDiscountPercent(new BigDecimal("7.50"));
        SalesSettingsRepository settingsRepo = org.mockito.Mockito.mock(SalesSettingsRepository.class);
        when(settingsRepo.findByCompanyId(COMPANY_ID)).thenReturn(Optional.of(stored));

        DiscountPolicyDto policy = new DiscountPolicyProvider(settingsRepo).policyFor(COMPANY_ID);

        assertThat(policy.isOff()).isFalse();
        assertThat(policy.action()).isEqualTo(DiscountApprovalAction.APPROVE);
        assertThat(policy.maxDiscountPercent()).isEqualByComparingTo("7.50");
        assertThat(policy.ceiling()).isEqualByComparingTo("7.50");
    }

    @Test
    void offPolicyAllowsEvenAOneHundredPercentDiscountAndAuditsNothing() {
        when(policies.policyFor(COMPANY_ID)).thenReturn(DiscountPolicyDto.off());

        Long authoriser = guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("1000"), null, null));

        assertThat(authoriser).isNull();
        verify(audit, never()).record(any());
        verify(users, never()).findByUid(any());
    }

    // -------------------------------------------------------------------------
    // The ceiling arithmetic.
    // -------------------------------------------------------------------------

    @Test
    void aDiscountAtOrUnderTheCeilingNeedsNoApproval() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        // Exactly on the ceiling: 100 off a 1000 line is 10%.
        assertThatCode(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("100"), null, null)))
                .doesNotThrowAnyException();
        // And below it, expressed as a percent.
        assertThatCode(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), null, new BigDecimal("9.5"), null)))
                .doesNotThrowAnyException();

        verify(audit, never()).record(any());
    }

    @Test
    void anAbsoluteAmountIsConvertedToAPercentOfTheLineGross() {
        // 250 off a 1000 line is 25% — over a 10% ceiling even though no percent was submitted.
        // This is the hole a percent-only check would leave wide open.
        assertThat(DiscountAuthorisationGuard.effectiveDiscountPercent(
                new BigDecimal("1000"), new BigDecimal("250"), null))
                .isEqualByComparingTo("25");
    }

    @Test
    void anAmountAgainstAZeroValueLineCountsAsOneHundredPercent() {
        // No meaningful ratio exists, and "free" is exactly what the ceiling is for — it must not
        // fall through the arithmetic as 0%.
        assertThat(DiscountAuthorisationGuard.effectiveDiscountPercent(
                BigDecimal.ZERO, new BigDecimal("50"), null))
                .isEqualByComparingTo("100");
    }

    @Test
    void noDiscountIsNeverChecked() {
        givenPolicy(DiscountApprovalAction.BLOCK, "0");

        assertThatCode(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), null, null, null)))
                .doesNotThrowAnyException();
        assertThatCode(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ZERO, null)))
                .doesNotThrowAnyException();
    }

    @Test
    void aLineWithNoDiscountDoesNotEvenReadThePolicy() {
        // The policy is a real query against sales_settings now (V95), and a POS basket calls this
        // guard once per line. An undiscounted line — the overwhelming majority — must not pay for
        // a lookup whose answer cannot change its outcome.
        givenPolicy(DiscountApprovalAction.BLOCK, "0");

        guard.authoriseLineDiscount(request(new BigDecimal("1000"), null, null, null));

        verify(policies, never()).policyFor(any());
    }

    @Test
    void aPolicyThatIsOnWithNoCeilingConfiguredRequiresApprovalForAnyDiscount() {
        // null ceiling must read as ZERO, not "unlimited" — switching the policy on has to DO
        // something, or the setting is a lie (the negative-stock toggle bug, replayed).
        when(policies.policyFor(COMPANY_ID))
                .thenReturn(new DiscountPolicyDto(DiscountApprovalAction.APPROVE, null));

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("1"), null, null)))
                .isInstanceOf(ConflictException.class);
    }

    // -------------------------------------------------------------------------
    // BLOCK / WARN
    // -------------------------------------------------------------------------

    @Test
    void blockRefusesEvenWithAValidManagerAttached() {
        givenPolicy(DiscountApprovalAction.BLOCK, "10");
        givenManagerWithAuthority();

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("10%")
                .hasMessageContaining("not allowed");
    }

    @Test
    void warnAllowsTheDiscountButLeavesAnAuditRow() {
        givenPolicy(DiscountApprovalAction.WARN, "10");

        Long authoriser = guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null));

        assertThat(authoriser).isNull();
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("SALES.DISCOUNT.WARNING");
        assertThat(captor.getValue().detail())
                .containsEntry("discountPercent", "50")
                .containsEntry("ceilingPercent", "10");
    }

    // -------------------------------------------------------------------------
    // APPROVE — the mode the client asked for.
    // -------------------------------------------------------------------------

    @Test
    void approveRefusesWhenNoManagerWasNamed() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("manager's approval")
                .hasMessageContaining("10%");
        verify(audit, never()).record(any());
    }

    @Test
    void aRefusedAttemptIsStillRecorded_inItsOwnTransaction() {
        // The rejection rolls the sale back, so a plain record() would roll back with it and the
        // attempt would vanish. "Cashier tried 50% off and was refused" is a shrinkage signal.
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null)))
                .isInstanceOf(ConflictException.class);

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).recordIndependent(captor.capture());
        assertThat(captor.getValue().detail())
                .containsEntry("outcome", "NO_APPROVAL_SUPPLIED")
                .containsEntry("discountPercent", "50");
    }

    @Test
    void approveAcceptsAManagerWhoHoldsTheOverridePermission() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");
        givenManagerWithAuthority();

        Long authoriser = guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID));

        assertThat(authoriser).isEqualTo(MANAGER_ID);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).record(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo("SALES.DISCOUNT.OVERRIDE");
        assertThat(captor.getValue().detail())
                .containsEntry("authoriserUsername", "manager")
                .containsEntry("discountPercent", "50");
    }

    @Test
    void aNamedUserWithoutTheOverridePermissionIsNotAnApproval() {
        // The uid alone is never enough — otherwise any client could name any colleague.
        givenPolicy(DiscountApprovalAction.APPROVE, "10");
        givenManager();
        when(permissionResolver.hasPermission(any(), eq("SALES.DISCOUNT.OVERRIDE"), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not accepted");
        verify(audit, never()).record(any());
    }

    @Test
    void anUnknownOrDeactivatedAuthoriserIsRefusedIdentically() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");
        when(users.findByUid(MANAGER_UID)).thenReturn(Optional.empty());

        String unknownMessage = messageOf(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID)));

        AppUser disabled = manager();
        disabled.setStatus(MasterStatus.INACTIVE);
        when(users.findByUid(MANAGER_UID)).thenReturn(Optional.of(disabled));

        String disabledMessage = messageOf(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID)));

        // A till must not become an oracle for which usernames exist or are still employed.
        assertThat(unknownMessage).isEqualTo(disabledMessage);
    }

    @Test
    void authorityIsResolvedInTheInvoicesCompanyNotTheCallers() {
        // Scope from the LOADED entity: a manager privileged in the caller's own company but not in
        // the invoice's company must not be able to approve this line.
        givenPolicy(DiscountApprovalAction.APPROVE, "10");
        givenManagerWithAuthority();
        when(policies.policyFor(OTHER_COMPANY))
                .thenReturn(new DiscountPolicyDto(DiscountApprovalAction.APPROVE, new BigDecimal("10")));

        guard.authoriseLineDiscount(new DiscountAuthorisationGuard.DiscountRequest(
                OTHER_COMPANY, INVOICE_ID, INVOICE_UID, "Sugar 1kg",
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID));

        ArgumentCaptor<RequestContext.Principal> principal =
                ArgumentCaptor.forClass(RequestContext.Principal.class);
        verify(permissionResolver).hasPermission(
                principal.capture(), eq("SALES.DISCOUNT.OVERRIDE"), anyLong());
        assertThat(principal.getValue().companyId()).isEqualTo(OTHER_COMPANY);
        assertThat(principal.getValue().userId()).isEqualTo(MANAGER_ID);
    }

    // -------------------------------------------------------------------------
    // Error hygiene: nothing internal reaches the operator.
    // -------------------------------------------------------------------------

    @Test
    void rejectionMessagesLeakNoInternalDetail() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        String message = messageOf(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null)));

        assertThat(message)
                .doesNotContain(INVOICE_UID)
                .doesNotContain(String.valueOf(INVOICE_ID))
                .doesNotContain(String.valueOf(COMPANY_ID))
                .doesNotContain("SALES.DISCOUNT.OVERRIDE")
                .doesNotContain("Exception")
                .doesNotContain("null")
                .doesNotContain("sales_invoice_lines");
    }

    // -------------------------------------------------------------------------
    // The refusal names itself (UAT finding #13).
    //
    // The web checkout used to decide whether to offer "Ask a supervisor" by searching this message
    // for the words "discount" and "approval". Reword a sentence and the button silently disappears,
    // stranding the cashier. Each refusal now carries a token the client branches on instead — and
    // the tokens must distinguish "a supervisor can fix this" from "nobody can", or the client
    // offers a remedy that would be refused anyway.
    // -------------------------------------------------------------------------

    @Test
    void aMissingApprovalIsRefusedWithTheCodeThatMeansASupervisorCanFixIt() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null)))
                .isInstanceOf(DiscountApprovalException.class)
                .extracting(e -> ((DiscountApprovalException) e).getCode())
                .isEqualTo(DiscountRefusalCode.DISCOUNT_APPROVAL_REQUIRED);
    }

    @Test
    void anUnusableApprovalIsRefusedWithItsOwnCode_soAnotherSupervisorMayStillTry() {
        givenPolicy(DiscountApprovalAction.APPROVE, "10");
        givenManager();
        when(permissionResolver.hasPermission(any(), eq("SALES.DISCOUNT.OVERRIDE"), anyLong()))
                .thenReturn(false);

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, MANAGER_UID)))
                .isInstanceOf(DiscountApprovalException.class)
                .extracting(e -> ((DiscountApprovalException) e).getCode())
                .isEqualTo(DiscountRefusalCode.DISCOUNT_APPROVAL_NOT_ACCEPTED);
    }

    @Test
    void blockIsRefusedWithACodeThatTellsTheClientNotToOfferAStepUp() {
        // The distinction that matters: prompting for an approval nobody may give is a lie.
        givenPolicy(DiscountApprovalAction.BLOCK, "10");

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null)))
                .isInstanceOf(DiscountApprovalException.class)
                .extracting(e -> ((DiscountApprovalException) e).getCode())
                .isEqualTo(DiscountRefusalCode.DISCOUNT_ABOVE_CEILING);
    }

    @Test
    void theCodeIsCarriedOnAConflictException_soEveryExistingHandlerBehavesAsBefore() {
        // Additive by construction: the type still IS a ConflictException, so the 409 mapping, the
        // POS flow wrapper and every caller that ignores the code are untouched.
        givenPolicy(DiscountApprovalAction.APPROVE, "10");

        assertThatThrownBy(() -> guard.authoriseLineDiscount(request(
                new BigDecimal("1000"), new BigDecimal("500"), null, null)))
                .isInstanceOf(ConflictException.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DiscountAuthorisationGuard.DiscountRequest request(BigDecimal lineGross,
                                                               BigDecimal amount,
                                                               BigDecimal percent,
                                                               String authoriserUid) {
        return new DiscountAuthorisationGuard.DiscountRequest(
                COMPANY_ID, INVOICE_ID, INVOICE_UID, "Sugar 1kg",
                lineGross, amount, percent, authoriserUid);
    }

    private void givenPolicy(DiscountApprovalAction action, String ceiling) {
        when(policies.policyFor(COMPANY_ID))
                .thenReturn(new DiscountPolicyDto(action, new BigDecimal(ceiling)));
    }

    private AppUser manager() {
        AppUser user = new AppUser("manager", "irrelevant-hash", "Maria Manager");
        setField(user, "id", MANAGER_ID);
        setField(user, "uid", MANAGER_UID);
        return user;
    }

    private void givenManager() {
        when(users.findByUid(MANAGER_UID)).thenReturn(Optional.of(manager()));
    }

    private void givenManagerWithAuthority() {
        givenManager();
        when(permissionResolver.hasPermission(any(), eq("SALES.DISCOUNT.OVERRIDE"), anyLong()))
                .thenReturn(true);
    }

    /**
     * Ids and uids are DB-assigned; reflection is the only way to give a detached entity one in a
     * unit test. Walks up to whichever superclass actually declares the field so the helper does not
     * silently break if the entity hierarchy gains a level.
     */
    private static void setField(Object entity, String name, Object value) {
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                field.set(entity, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Could not set " + name, e);
            }
        }
        throw new IllegalStateException("No field '" + name + "' on " + entity.getClass());
    }

    private static String messageOf(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            return e.getMessage();
        }
        throw new AssertionError("Expected the guard to refuse, but it allowed the discount.");
    }
}
