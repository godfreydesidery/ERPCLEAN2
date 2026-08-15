package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.AuthorityVerificationDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.config.SecurityProperties;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for {@link StepUpAuthServiceImpl} — manager step-up ("supervisor override").
 *
 * <p>Covers the four contract properties the POS and web clients depend on:
 * <ul>
 *   <li>right password + holds the permission → authorised, with the authoriser's identity;
 *   <li>right password + lacks the permission → NOT authorised;
 *   <li>wrong password (or unknown / locked account) → NOT authorised, one generic message;
 *   <li>no token is issued or rotated and the caller's session is untouched.
 * </ul>
 * Plus the deliberate lockout divergence: a failed step-up never increments the AUTHORISER's
 * failed-login counter (a cashier must not be able to lock their supervisor out); the CALLER is
 * throttled instead.
 *
 * <p>Style mirrors {@link UserServiceImplTest} (mock repos + RequestContext.set/clear).
 * K1/K7 (Kilimanjaro feedback) consume this endpoint.
 */
class StepUpAuthServiceImplTest {

    private static final Long   ORG_ID      = 9L;
    private static final Long   CALLER_ID   = 7L;
    private static final Long   COMPANY_ID  = 10L;
    private static final Long   BRANCH_ID   = 100L;
    private static final Long   MANAGER_ID  = 42L;
    private static final String MANAGER_UID = "uid-manager-001";
    private static final String CODE        = "SALES.INVOICE.OVERRIDE";
    private static final int    MAX_ATTEMPTS = 5;

    private AppUserRepository     users;
    private PermissionRepository  permissions;
    private PasswordEncoder       passwordEncoder;
    private PermissionResolver    permissionResolver;
    private AuditService          audit;
    private StepUpAuthServiceImpl service;

    @BeforeEach
    void setUp() {
        users              = mock(AppUserRepository.class);
        permissions        = mock(PermissionRepository.class);
        passwordEncoder    = mock(PasswordEncoder.class);
        permissionResolver = mock(PermissionResolver.class);
        audit              = mock(AuditService.class);
        service = new StepUpAuthServiceImpl(
                users,
                permissions,
                passwordEncoder,
                permissionResolver,
                audit,
                new SecurityProperties(
                        new SecurityProperties.Lockout(MAX_ATTEMPTS, 15),
                        new SecurityProperties.Password(12)),
                new com.erp.platform.security.TenancyScopeEnforcer());
        RequestContext.set(cashier());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private RequestContext.Principal cashier() {
        return new RequestContext.Principal(
                CALLER_ID, "cashier", false, COMPANY_ID, BRANCH_ID, "127.0.0.1", ORG_ID);
    }

    /** An ACTIVE, unlocked manager account whose password hash is {@code "hash"}. */
    private AppUser manager() {
        AppUser u = mock(AppUser.class);
        // Same tenant as the caller. Must be stubbed: Mockito returns 0L, not null, for an
        // unstubbed Long-returning method, which would read as a DIFFERENT organisation.
        when(u.getOrganisationId()).thenReturn(ORG_ID);
        when(u.getId()).thenReturn(MANAGER_ID);
        when(u.getUid()).thenReturn(MANAGER_UID);
        when(u.getUsername()).thenReturn("manager");
        when(u.getDisplayName()).thenReturn("Maria Manager");
        when(u.getPasswordHash()).thenReturn("hash");
        when(u.isActive()).thenReturn(true);
        when(u.isLocked(any(Instant.class))).thenReturn(false);
        return u;
    }

    private void givenManagerExists(AppUser m) {
        when(users.findByUsername("manager")).thenReturn(Optional.of(m));
    }

    private void givenPasswordMatches(boolean matches) {
        when(passwordEncoder.matches("secret", "hash")).thenReturn(matches);
    }

    private void givenPermissionSeeded() {
        when(permissions.findByCode(CODE)).thenReturn(Optional.of(mock(Permission.class)));
    }

    private void givenAuthoriserHoldsPermission(boolean holds) {
        when(permissionResolver.hasPermission(any(), eq(CODE), anyLong())).thenReturn(holds);
    }

    private AuditEvent capturedAudit() {
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit, atLeastOnce()).record(captor.capture());
        return captor.getValue();
    }

    // -----------------------------------------------------------------------
    // Happy path
    // -----------------------------------------------------------------------

    @Test
    void correctPasswordAndHoldsPermission_isAuthorised() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto result = service.verifyAuthority("Manager", "secret", CODE);

        assertThat(result.authorised()).isTrue();
        assertThat(result.permissionCode()).isEqualTo(CODE);
        assertThat(result.authoriserUid()).isEqualTo(MANAGER_UID);
        assertThat(result.authoriserUsername()).isEqualTo("manager");
        assertThat(result.authoriserName()).isEqualTo("Maria Manager");
        assertThat(result.message()).contains("Maria Manager");

        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo(AuditActions.AUTH_STEP_UP_SUCCESS);
        assertThat(event.targetUid()).isEqualTo(MANAGER_UID);
        assertThat(event.detail()).containsEntry("permissionCode", CODE);
    }

    @Test
    void authorityIsResolvedInTheCallersScope_notAScopeFromTheBody() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        service.verifyAuthority("manager", "secret", CODE);

        ArgumentCaptor<RequestContext.Principal> captor =
                ArgumentCaptor.forClass(RequestContext.Principal.class);
        verify(permissionResolver).hasPermission(captor.capture(), eq(CODE), anyLong());
        RequestContext.Principal resolved = captor.getValue();
        // The AUTHORISER's identity...
        assertThat(resolved.userId()).isEqualTo(MANAGER_ID);
        // ...evaluated in the CALLER's company/branch (the till), never a caller-supplied scope.
        assertThat(resolved.companyId()).isEqualTo(COMPANY_ID);
        assertThat(resolved.branchId()).isEqualTo(BRANCH_ID);
    }

    // -----------------------------------------------------------------------
    // Refusals
    // -----------------------------------------------------------------------

    @Test
    void correctPasswordButLacksPermission_isNotAuthorised() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(false);

        AuthorityVerificationDto result = service.verifyAuthority("manager", "secret", CODE);

        assertThat(result.authorised()).isFalse();
        assertThat(result.authoriserUid()).isNull();
        assertThat(result.authoriserName()).isNull();
        assertThat(result.message()).isEqualTo("That user is not allowed to approve this action.");

        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo(AuditActions.AUTH_STEP_UP_FAIL);
        assertThat(event.detail()).containsEntry("outcome", "NO_AUTHORITY");
        // G11 (ADR-0062 P3-9) REVERSED THIS. It used to assert false, on the reasoning that a
        // right-password refusal is not a guess. That reasoning was the oracle: an operator could
        // try a colleague's password against a permission that colleague does not hold, and every
        // correct guess came back distinguishable from a wrong one against no counter at all — then
        // be reused at the main login. The message stays distinct so a manager who genuinely lacks
        // the permission is not told their password is wrong; only the unlimited guessing is gone.
        assertThat(event.detail()).containsEntry("throttleCounted", true);
    }

    @Test
    void wrongPassword_isNotAuthorised_andNeverResolvesPermissions() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(false);

        AuthorityVerificationDto result = service.verifyAuthority("manager", "secret", CODE);

        assertThat(result.authorised()).isFalse();
        assertThat(result.authoriserUid()).isNull();
        assertThat(result.message())
                .isEqualTo("Those details were not accepted. "
                        + "Check the username and password and try again.");
        // Fail closed before authority is even considered.
        verify(permissionResolver, never()).hasPermission(any(), anyString(), anyLong());

        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo(AuditActions.AUTH_STEP_UP_FAIL);
        assertThat(event.detail()).containsEntry("outcome", "BAD_PASSWORD");
    }

    @Test
    void unknownUsername_returnsTheSameGenericMessageAsAWrongPassword() {
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());

        AuthorityVerificationDto unknown = service.verifyAuthority("ghost", "secret", CODE);

        assertThat(unknown.authorised()).isFalse();
        assertThat(unknown.message())
                .isEqualTo("Those details were not accepted. "
                        + "Check the username and password and try again.");
        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo(AuditActions.AUTH_STEP_UP_FAIL);
        assertThat(event.detail()).containsEntry("outcome", "UNKNOWN_USER");
        // No target row for an account that does not exist (mirrors the unknown-user login path).
        assertThat(event.targetUid()).isNull();
    }

    @Test
    void lockedAuthoriser_cannotApproveEvenWithTheRightPassword() {
        AppUser m = manager();
        when(m.isLocked(any(Instant.class))).thenReturn(true);
        givenManagerExists(m);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto result = service.verifyAuthority("manager", "secret", CODE);

        assertThat(result.authorised()).isFalse();
        assertThat(capturedAudit().detail()).containsEntry("outcome", "ACCOUNT_UNAVAILABLE");
    }

    @Test
    void unseededPermissionCode_isNeverApproved() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(true);
        when(permissions.findByCode("TYPO.CODE")).thenReturn(Optional.empty());

        AuthorityVerificationDto result = service.verifyAuthority("manager", "secret", "TYPO.CODE");

        assertThat(result.authorised()).isFalse();
        assertThat(capturedAudit().detail()).containsEntry("outcome", "UNKNOWN_PERMISSION");
        verify(permissionResolver, never()).hasPermission(any(), anyString(), anyLong());
    }

    // -----------------------------------------------------------------------
    // Lockout policy: the cashier cannot lock the manager out
    // -----------------------------------------------------------------------

    @Test
    void failedStepUp_neverTouchesTheAuthorisersLockoutState() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(false);

        for (int i = 0; i < MAX_ATTEMPTS + 3; i++) {
            service.verifyAuthority("manager", "secret", CODE);
        }

        // The authoriser's own lockout bookkeeping is untouched and nothing is written back.
        verify(m, never()).registerFailedLogin(anyInt(), anyInt(), any(Instant.class));
        verify(users, never()).save(any(AppUser.class));
    }

    @Test
    void repeatedRightPasswordNoAuthorityAttempts_alsoThrottle_closingTheG11Oracle() {
        // THE POINT OF P3-9. Before this, these attempts were free: the password was proven, so the
        // refusal was classified as "not a credential failure" and fed no counter. An operator could
        // therefore stand at the till and confirm a colleague's password an unlimited number of
        // times — every correct guess visibly different from a wrong one — and then use it at the
        // main login, where the only real throttle lives. Unlimited verification of a credential is
        // a credential oracle whatever the endpoint calls it.
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(true);          // the password is RIGHT every single time
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(false);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(service.verifyAuthority("manager", "secret", CODE).authorised()).isFalse();
        }

        // Threshold reached on right-password attempts alone. If this ever reads NO_AUTHORITY again,
        // the oracle is back.
        AuthorityVerificationDto throttled = service.verifyAuthority("manager", "secret", CODE);

        assertThat(throttled.authorised()).isFalse();
        assertThat(throttled.message())
                .isEqualTo("Too many failed approval attempts. Please wait a moment and try again.");
        assertThat(capturedAudit().detail()).containsEntry("outcome", "THROTTLED");

        // The throttle counts against the CALLER, never the authoriser: a cashier still cannot lock
        // a manager out of the product by guessing at them.
        verify(m, never()).registerFailedLogin(anyInt(), anyInt(), any(Instant.class));
    }

    @Test
    void repeatedCredentialFailures_throttleTheCaller() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(false);

        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            assertThat(service.verifyAuthority("manager", "secret", CODE).authorised()).isFalse();
        }

        // Threshold reached: even a CORRECT password is refused during the cooldown.
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto throttled = service.verifyAuthority("manager", "secret", CODE);

        assertThat(throttled.authorised()).isFalse();
        assertThat(throttled.message())
                .isEqualTo("Too many failed approval attempts. Please wait a moment and try again.");
        assertThat(capturedAudit().detail()).containsEntry("outcome", "THROTTLED");
    }

    @Test
    void aSuccessfulStepUp_clearsTheCallersFailureCount() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        givenPasswordMatches(false);
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            service.verifyAuthority("manager", "secret", CODE);
        }
        givenPasswordMatches(true);
        assertThat(service.verifyAuthority("manager", "secret", CODE).authorised()).isTrue();

        // Counter reset: a fresh run of failures is needed before the cooldown bites again.
        givenPasswordMatches(false);
        for (int i = 0; i < MAX_ATTEMPTS - 1; i++) {
            assertThat(service.verifyAuthority("manager", "secret", CODE).message())
                    .doesNotContain("Too many");
        }
    }

    // -----------------------------------------------------------------------
    // The load-bearing property: no session is created, rotated or disturbed
    // -----------------------------------------------------------------------

    @Test
    void stepUpIssuesNoTokenAndLeavesTheCallersSessionIntact() {
        AppUser m = manager();
        givenManagerExists(m);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);
        RequestContext.Principal before = RequestContext.get();

        AuthorityVerificationDto result = service.verifyAuthority("manager", "secret", CODE);

        // 1. The cashier is still the principal, in the same company/branch — nothing was swapped.
        assertThat(RequestContext.get()).isSameAs(before);
        assertThat(RequestContext.get().userId()).isEqualTo(CALLER_ID);
        assertThat(RequestContext.get().companyId()).isEqualTo(COMPANY_ID);
        assertThat(RequestContext.get().branchId()).isEqualTo(BRANCH_ID);

        // 2. The response carries no token material of any kind.
        assertThat(Arrays.stream(AuthorityVerificationDto.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .map(n -> n.toLowerCase(java.util.Locale.ROOT))
                        .filter(n -> n.contains("token"))
                        .toList())
                .isEmpty();
        assertThat(result.authorised()).isTrue();
    }

    @Test
    void stepUpServiceCannotMintOrRotateTokens_structurally() {
        // The strongest form of "issues no token": the collaborators that could are not wired in.
        // If someone adds JwtService / RefreshTokenRepository here later, this fails immediately.
        Constructor<?> ctor = StepUpAuthServiceImpl.class.getDeclaredConstructors()[0];
        assertThat(Arrays.stream(ctor.getParameterTypes()).map(Class::getName).toList())
                .as("step-up must not depend on token machinery")
                .noneMatch(StepUpAuthServiceImplTest::isTokenMachinery);

        assertThat(Arrays.stream(StepUpAuthServiceImpl.class.getDeclaredFields())
                        .map(Field::getType)
                        .map(Class::getName)
                        .toList())
                .as("step-up must not hold token machinery in a field")
                .noneMatch(StepUpAuthServiceImplTest::isTokenMachinery);
    }

    private static boolean isTokenMachinery(String className) {
        return className.contains("JwtService")
                || className.contains("RefreshToken")
                || className.contains("TokenResponse");
    }

    // =======================================================================
    // B4 — nobody may approve themselves.
    //
    // Verified live before the fix: uat_cashier called verify-authority with their OWN username and
    // password and POS.SESSION.RECONCILE, and got authorised=true, "Authorised by UAT Cashier."
    // CASHIER holds POS.SESSION.RECONCILE (it must, to close its own shift), so the single action
    // the step-up was introduced to protect was the one action it did not protect.
    // =======================================================================

    // =======================================================================
    // P2-3 (ADR-0062) — a manager from ANOTHER tenant cannot approve here.
    //
    // findByUsername and findByUid are global lookups, so before this the only thing standing
    // between a supermarket's till and a supervisor at a different customer was that nobody had
    // tried. The refusal is deliberately identical to the unknown-user one: a distinct message or
    // a different response time would confirm that the account exists somewhere else, which is the
    // enumeration this endpoint is otherwise careful to prevent.
    // =======================================================================

    @Test
    void anAuthoriserFromAnotherTenantIsRefused_andIndistinguishableFromAnUnknownUser() {
        AppUser foreign = manager();
        when(foreign.getOrganisationId()).thenReturn(ORG_ID + 1);   // a different customer
        givenManagerExists(foreign);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto foreignTenant = service.verifyAuthority("Manager", "secret", CODE);

        // Same outcome, and the SAME MESSAGE, as a username that does not exist at all.
        when(users.findByUsername("ghost")).thenReturn(Optional.empty());
        AuthorityVerificationDto unknownUser = service.verifyAuthority("ghost", "secret", CODE);

        assertThat(foreignTenant.authorised()).isFalse();
        assertThat(foreignTenant.message()).isEqualTo(unknownUser.message());
        assertThat(foreignTenant.authoriserUid()).isNull();
    }

    /** The caller's own account, as the step-up would resolve it from their own username. */
    private AppUser self() {
        AppUser u = mock(AppUser.class);
        when(u.getOrganisationId()).thenReturn(ORG_ID);   // same tenant as the caller
        when(u.getId()).thenReturn(CALLER_ID);
        when(u.getUid()).thenReturn("uid-cashier-001");
        when(u.getUsername()).thenReturn("cashier");
        when(u.getDisplayName()).thenReturn("UAT Cashier");
        when(u.getPasswordHash()).thenReturn("hash");
        when(u.isActive()).thenReturn(true);
        when(u.isLocked(any(Instant.class))).thenReturn(false);
        return u;
    }

    @Test
    void authoriserIsTheCaller_isRefusedEvenWithTheRightPasswordAndTheAuthority() {
        AppUser me = self();
        when(users.findByUsername("cashier")).thenReturn(Optional.of(me));
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);   // the caller genuinely holds the code

        AuthorityVerificationDto result = service.verifyAuthority("Cashier", "secret", CODE);

        assertThat(result.authorised())
                .as("an over-the-shoulder approval involves a second person by definition")
                .isFalse();
        assertThat(result.authoriserUid()).isNull();
        assertThat(result.authoriserUsername()).isNull();
    }

    @Test
    void selfApproval_reusesTheExistingNotAuthorisedMessageAndAuditsAsAFailure() {
        AppUser me = self();
        when(users.findByUsername("cashier")).thenReturn(Optional.of(me));
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto result = service.verifyAuthority("cashier", "secret", CODE);

        // Same wording as "that user cannot approve this" — a fumbled self-approval must never read
        // as a hint about who to fetch instead.
        assertThat(result.message())
                .isEqualTo("That user is not allowed to approve this action.");

        AuditEvent event = capturedAudit();
        assertThat(event.action()).isEqualTo(AuditActions.AUTH_STEP_UP_FAIL);
        assertThat(event.detail()).containsEntry("outcome", "SELF_APPROVAL");
        // Still uncounted, unlike NO_AUTHORITY and UNKNOWN_PERMISSION since P3-9 — and the reason is
        // not "the password was right" but "the authoriser IS the caller". A fumbled self-approval
        // teaches them only their own password, so there is no oracle to close, and throttling an
        // operator for a mis-tap would be pure friction at a till...
        assertThat(event.detail()).containsEntry("throttleCounted", false);
        // ...and it must not touch anybody's lockout counter.
        verify(me, never()).registerFailedLogin(anyInt(), anyInt(), any(Instant.class));
    }

    @Test
    void aDifferentManagerStillApproves_theSelfCheckIsNotABlanketRefusal() {
        AppUser m = manager();                  // id 42, caller is 7
        givenManagerExists(m);
        givenPasswordMatches(true);
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        assertThat(service.verifyAuthority("manager", "secret", CODE).authorised()).isTrue();
    }

    // =======================================================================
    // C3(b) — the uid re-verification the business requests carry.
    //
    // The password prompt lived entirely in the till app, so curl reached the same endpoint with no
    // manager involved. The approval now travels on the request and is re-resolved here.
    // =======================================================================

    private void givenManagerFoundByUid(AppUser m) {
        when(users.findByUid(MANAGER_UID)).thenReturn(Optional.of(m));
    }

    @Test
    void verifyAuthoriserUid_realDifferentManagerWhoHoldsTheCode_isAuthorised() {
        givenManagerFoundByUid(manager());
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto result =
                service.verifyAuthoriserUid(MANAGER_UID, CODE, COMPANY_ID);

        assertThat(result.authorised()).isTrue();
        assertThat(result.authoriserUsername()).isEqualTo("manager");
        // No password was typed, so nothing here can be brute-forced — and nothing is encoded.
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void verifyAuthoriserUid_fabricatedUid_isRefused() {
        when(users.findByUid("01JFAKEFAKEFAKEFAKEFAKEFAK")).thenReturn(Optional.empty());

        AuthorityVerificationDto result =
                service.verifyAuthoriserUid("01JFAKEFAKEFAKEFAKEFAKEFAK", CODE, COMPANY_ID);

        assertThat(result.authorised())
                .as("a uid invented by a client is not an approval")
                .isFalse();
        assertThat(result.message())
                .isEqualTo("That user is not allowed to approve this action.");
    }

    @Test
    void verifyAuthoriserUid_missingUid_isRefused() {
        assertThat(service.verifyAuthoriserUid(null, CODE, COMPANY_ID).authorised()).isFalse();
        assertThat(service.verifyAuthoriserUid("  ", CODE, COMPANY_ID).authorised()).isFalse();
        verify(users, never()).findByUid(anyString());
    }

    @Test
    void verifyAuthoriserUid_theCallerNamingThemselves_isRefused() {
        AppUser me = self();
        when(users.findByUid("uid-cashier-001")).thenReturn(Optional.of(me));
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);

        AuthorityVerificationDto result =
                service.verifyAuthoriserUid("uid-cashier-001", CODE, COMPANY_ID);

        assertThat(result.authorised())
                .as("self-approval must be closed on BOTH step-up entry points, or the one that "
                        + "stayed open is the one that gets used")
                .isFalse();
    }

    @Test
    void verifyAuthoriserUid_namedUserLacksTheCode_isRefused() {
        givenManagerFoundByUid(manager());
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(false);

        assertThat(service.verifyAuthoriserUid(MANAGER_UID, CODE, COMPANY_ID).authorised())
                .isFalse();
    }

    @Test
    void verifyAuthoriserUid_unseededCode_isRefused() {
        givenManagerFoundByUid(manager());
        when(permissions.findByCode(CODE)).thenReturn(Optional.empty());
        givenAuthoriserHoldsPermission(true);

        assertThat(service.verifyAuthoriserUid(MANAGER_UID, CODE, COMPANY_ID).authorised())
                .as("a typo'd or unseeded code must never read as approved")
                .isFalse();
    }

    @Test
    void verifyAuthoriserUid_resolvesAuthorityInTheDocumentsCompany_notTheCallers() {
        givenManagerFoundByUid(manager());
        givenPermissionSeeded();
        givenAuthoriserHoldsPermission(true);
        Long documentCompany = 999L;            // the LOADED document's company, not the caller's

        service.verifyAuthoriserUid(MANAGER_UID, CODE, documentCompany);

        ArgumentCaptor<RequestContext.Principal> captor =
                ArgumentCaptor.forClass(RequestContext.Principal.class);
        verify(permissionResolver).hasPermission(captor.capture(), eq(CODE), anyLong());
        assertThat(captor.getValue().userId()).isEqualTo(MANAGER_ID);
        assertThat(captor.getValue().companyId()).isEqualTo(documentCompany);
        assertThat(captor.getValue().branchId()).isEqualTo(BRANCH_ID);
    }

    @Test
    void verifyAuthoriserUid_refusalIsAuditedIndependentlyOfTheRolledBackTransaction() {
        when(users.findByUid("01JFAKEFAKEFAKEFAKEFAKEFAK")).thenReturn(Optional.empty());

        service.verifyAuthoriserUid("01JFAKEFAKEFAKEFAKEFAKEFAK", CODE, COMPANY_ID);

        // The business transaction that asked is about to roll back; a plain record() would roll the
        // evidence back with it.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(audit).recordIndependent(captor.capture());
        assertThat(captor.getValue().action()).isEqualTo(AuditActions.AUTH_STEP_UP_FAIL);
        assertThat(captor.getValue().detail())
                .containsEntry("outcome", "AUTHORISER_UNAVAILABLE");
    }
}
