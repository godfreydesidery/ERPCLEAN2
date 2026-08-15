package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.config.SecurityErrorResponder;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Locks the three controls added to {@link JwtRequestContextFilter} in Phase 3 batch 3
 * (ADR-0062: P3-1 tenant gate on the branch override, P3-13 root read from the row, P3-14 the
 * assignment must still be live).
 *
 * <p>All three sit on the same few lines, and all three fail <b>open</b> if removed — the request
 * simply proceeds with a principal it should never have been given. Nothing downstream would throw,
 * so without this class a regression here is silent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("the X-Branch-Uid override")
class BranchOverrideTenancyTest {

    private static final long USER_ID = 7L;
    private static final long HOME_ORG = 1L;
    private static final long OTHER_ORG = 2L;
    private static final long HOME_COMPANY = 10L;
    private static final long TARGET_COMPANY = 20L;
    private static final long TARGET_BRANCH = 200L;
    private static final String TARGET_BRANCH_UID = "01TARGETBRANCH0000000000AA";

    @Mock private BranchRepository branches;
    @Mock private UserBranchRepository userBranches;
    @Mock private AppUserRepository appUsers;
    @Mock private SecurityErrorResponder errorResponder;
    @Mock private CompanyTenantIndex companyTenants;
    @Mock private AuditService audit;
    @Mock private FilterChain chain;

    private JwtRequestContextFilter filter;
    private Branch targetBranch;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new JwtRequestContextFilter(branches, userBranches, appUsers, errorResponder,
                new TenancyScopeEnforcer(), companyTenants, audit);

        Company target = new Company(new Organisation("Other Tenant"), "C2", "Target Co");
        setId(target, TARGET_COMPANY);
        Branch branch = new Branch(target, "BR-T", "Target Branch");
        setId(branch, TARGET_BRANCH);
        when(branches.findWithCompanyByUid(TARGET_BRANCH_UID)).thenReturn(Optional.of(branch));
        this.targetBranch = branch;

        request = new MockHttpServletRequest();
        request.addHeader("X-Branch-Uid", TARGET_BRANCH_UID);
        response = new MockHttpServletResponse();

        authenticateAs(USER_ID, HOME_COMPANY);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContext.clear();
    }

    /** The user row as the filter reads it: organisation and root come from HERE, not the token. */
    private void userRow(Long organisationId, boolean root) {
        AppUserRepository.ActiveUserScope scope = new AppUserRepository.ActiveUserScope() {
            @Override public Long getOrganisationId() { return organisationId; }
            @Override public boolean getRoot() { return root; }
        };
        when(appUsers.findActiveScope(anyLong(), any())).thenReturn(Optional.of(scope));
    }

    private void authenticateAs(long userId, long companyId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(userId))
                .claim("username", "cashier")
                .claim("companyId", String.valueOf(companyId))
                .claim("branchId", "100")
                .claim("isRoot", Boolean.TRUE)   // the token LIES — P3-13 must ignore it
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private void liveAssignment() {
        when(userBranches.findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue(USER_ID, TARGET_BRANCH))
                .thenReturn(Optional.of(new UserBranch(USER_ID, targetBranch, null)));
    }

    @Test
    @DisplayName("P3-1 · a branch in another organisation is refused, root included")
    void crossTenantOverrideIsRefused() throws Exception {
        userRow(HOME_ORG, true);                                   // root, per the DATABASE
        when(companyTenants.organisationOf(TARGET_COMPANY)).thenReturn(OTHER_ORG);
        liveAssignment();                                          // assignment is fine — tenancy is not

        filter.doFilter(request, response, chain);

        // The chain must not run: a session built on a foreign company would have P3-11 refuse every
        // action inside it, which reads as a broken screen rather than as a boundary.
        verify(chain, never()).doFilter(any(), any());
        verify(errorResponder).handle(any(), any(), any());
        verify(audit, never()).recordIndependent(any());
    }

    @Test
    @DisplayName("P3-1 · a branch in the caller's own organisation is allowed, and audited")
    void sameTenantOverrideIsAllowed() throws Exception {
        userRow(HOME_ORG, false);
        when(companyTenants.organisationOf(TARGET_COMPANY)).thenReturn(HOME_ORG);
        liveAssignment();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        // P3-2: the switch left the company minted at login, so it must leave a trace. Before this,
        // ScopeGuard's ROOT_BYPASS could not fire here — after the switch the principal's company IS
        // the target, so its "target differs" test is false and no row was ever written.
        verify(audit).recordIndependent(any());
    }

    @Test
    @DisplayName("P3-13 · root comes from the row, so a token claiming isRoot does not skip P3-14")
    void rootIsReadFromTheRowNotTheToken() throws Exception {
        // The token says isRoot=true; the row says false. Under the old code the claim won, and the
        // claim skips the assignment check entirely — a demoted root kept superuser reach until
        // their token expired, during exactly the incident being contained.
        userRow(HOME_ORG, false);
        when(companyTenants.organisationOf(TARGET_COMPANY)).thenReturn(HOME_ORG);
        when(userBranches.findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue(USER_ID, TARGET_BRANCH))
                .thenReturn(Optional.empty());                     // not assigned

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        verify(errorResponder).handle(any(), any(), any());
    }

    @Test
    @DisplayName("P3-14 · a revoked assignment no longer permits the switch")
    void revokedAssignmentIsRefused() throws Exception {
        userRow(HOME_ORG, false);
        when(companyTenants.organisationOf(TARGET_COMPANY)).thenReturn(HOME_ORG);

        // The revocation-blind finder still returns the row — that is the bug this closes. The
        // revocation-aware one does not, and it is the one the filter must consult.
        UserBranch revoked = new UserBranch(USER_ID, targetBranch, null);
        revoked.revoke(Instant.now());
        when(userBranches.findByUserIdAndBranchId(USER_ID, TARGET_BRANCH)).thenReturn(Optional.of(revoked));
        when(userBranches.findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue(USER_ID, TARGET_BRANCH))
                .thenReturn(Optional.empty());

        filter.doFilter(request, response, chain);

        verify(chain, never()).doFilter(any(), any());
        assertThat(revoked.getRevokedAt()).as("fixture sanity: the assignment really is revoked").isNotNull();
    }

    /** Ids are set by the persistence provider; tests need them without touching production code. */
    private static void setId(Object entity, Long id) {
        for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot set id on " + entity.getClass(), e);
            }
        }
        throw new IllegalStateException("no id field on " + entity.getClass());
    }
}
