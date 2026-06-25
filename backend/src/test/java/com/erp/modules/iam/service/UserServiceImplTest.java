package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.password.PasswordPolicy;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for the tenant-isolation and root-visibility fixes in {@link UserServiceImpl}:
 * <ul>
 *   <li>{@link UserServiceImpl#list()} — root-excluding company-scoped for non-root; org-wide
 *       (all users) for root; fail-closed when companyId is null or principal is null.
 *   <li>{@link UserServiceImpl#listOrgWide()} — root-excluding org-wide for non-root; all for root.
 *   <li>{@link UserServiceImpl#getByUid(String)} — 404 for out-of-company target (non-root); 404
 *       when target is a root user and caller is non-root; OK for same-company and root callers.
 * </ul>
 *
 * <p>Style: mirrors {@link CompanyServiceImplTest} (mock repos + RequestContext.set/clear).
 * Security audit 2026-06-25; root-visibility hardening 2026-06-25.
 */
class UserServiceImplTest {

    private static final Long   COMPANY_A = 10L;
    private static final Long   COMPANY_B = 20L;
    private static final Long   USER_ID   = 42L;
    private static final String USER_UID  = "uid-user-001";

    private AppUserRepository userRepo;
    private UserServiceImpl   service;

    @BeforeEach
    void setUp() {
        userRepo = mock(AppUserRepository.class);
        service  = new UserServiceImpl(
                userRepo,
                mock(PasswordEncoder.class),
                mock(PasswordPolicy.class),
                mock(AuditService.class));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private AppUser stubUser(Long id, String uid, String username) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.getUid()).thenReturn(uid);
        when(u.getUsername()).thenReturn(username);
        when(u.getDisplayName()).thenReturn("Display " + username);
        when(u.getStatus()).thenReturn(MasterStatus.ACTIVE);
        return u;
    }

    private RequestContext.Principal nonRoot(Long companyId) {
        return new RequestContext.Principal(USER_ID, "alice", false, companyId, null, "127.0.0.1");
    }

    private RequestContext.Principal root() {
        return new RequestContext.Principal(1L, "root", true, null, null, "127.0.0.1");
    }

    // -----------------------------------------------------------------------
    // list() — company-scoped
    // -----------------------------------------------------------------------

    @Test
    void list_nonRoot_companyScoped_excludesOtherCompanyUser() {
        AppUser inA = stubUser(1L, "uid-a", "alice");

        // Non-root path uses the root-excluding company query.
        when(userRepo.findNonRootInCompanyOrderByUsername(COMPANY_A)).thenReturn(List.of(inA));
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
        verify(userRepo, never()).findAllByOrderByUsername();
        // bob (company B) absent — not returned by the scoped query
        assertThat(result).noneMatch(d -> d.uid().equals("uid-b"));
    }

    @Test
    void list_nonRoot_excludesRootUser_evenIfInCompany() {
        // The repository method bakes out root=false; mock returns only non-root users.
        AppUser normalUser = stubUser(1L, "uid-a", "alice");

        when(userRepo.findNonRootInCompanyOrderByUsername(COMPANY_A)).thenReturn(List.of(normalUser));
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.list();

        // Root user is not in the result (the repo excludes it at the query level).
        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
        // Root-excluding repo method was called, not the plain one.
        verify(userRepo).findNonRootInCompanyOrderByUsername(COMPANY_A);
        verify(userRepo, never()).findAllByOrderByUsername();
    }

    @Test
    void list_nonRoot_multiCompanyUser_includedViaCompanyAQuery() {
        AppUser multi = stubUser(3L, "uid-multi", "charlie");

        when(userRepo.findNonRootInCompanyOrderByUsername(COMPANY_A)).thenReturn(List.of(multi));
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-multi");
    }

    @Test
    void list_root_seesAllOrgWide_includingRootUsers() {
        AppUser u1   = stubUser(1L, "uid-a", "alice");
        AppUser root = stubUser(2L, "uid-root", "rootadmin");
        when(root.isRoot()).thenReturn(true);

        when(userRepo.findAllByOrderByUsername()).thenReturn(List.of(u1, root));
        RequestContext.set(root());

        List<UserDto> result = service.list();

        assertThat(result).hasSize(2);
        verify(userRepo, never()).findNonRootInCompanyOrderByUsername(COMPANY_A);
    }

    @Test
    void list_nonRoot_nullCompany_returnsEmpty_failClosed() {
        RequestContext.set(nonRoot(null));

        List<UserDto> result = service.list();

        assertThat(result).isEmpty();
        verify(userRepo, never()).findAllByOrderByUsername();
        verify(userRepo, never()).findNonRootInCompanyOrderByUsername(null);
    }

    @Test
    void list_nullPrincipal_failClosed_returnsEmpty() {
        // Null principal has no company context → fail-closed empty list (same as non-root/null-company).
        // No RequestContext set → principal is null.

        List<UserDto> result = service.list();

        assertThat(result).isEmpty();
        verify(userRepo, never()).findAllByOrderByUsername();
        verify(userRepo, never()).findNonRootInCompanyOrderByUsername(null);
    }

    // -----------------------------------------------------------------------
    // listOrgWide()
    // -----------------------------------------------------------------------

    @Test
    void listOrgWide_nonRoot_excludesRootUser() {
        AppUser normalUser = stubUser(1L, "uid-a", "alice");
        // Root-excluding repo method returns only non-root users.
        when(userRepo.findByRootFalseOrderByUsername()).thenReturn(List.of(normalUser));
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.listOrgWide();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
        verify(userRepo).findByRootFalseOrderByUsername();
        verify(userRepo, never()).findAllByOrderByUsername();
    }

    @Test
    void listOrgWide_root_includesRootUsers() {
        AppUser u1   = stubUser(1L, "uid-a", "alice");
        AppUser root = stubUser(2L, "uid-root", "rootadmin");
        when(root.isRoot()).thenReturn(true);
        when(userRepo.findAllByOrderByUsername()).thenReturn(List.of(u1, root));
        RequestContext.set(root());

        List<UserDto> result = service.listOrgWide();

        assertThat(result).hasSize(2);
        verify(userRepo).findAllByOrderByUsername();
        verify(userRepo, never()).findByRootFalseOrderByUsername();
    }

    // -----------------------------------------------------------------------
    // getByUid() — tenant-isolation guard + root-visibility hardening
    // -----------------------------------------------------------------------

    @Test
    void getByUid_root_alwaysResolves() {
        AppUser user = stubUser(USER_ID, USER_UID, "alice");
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        RequestContext.set(root());

        UserDto result = service.getByUid(USER_UID);

        assertThat(result.uid()).isEqualTo(USER_UID);
        // existsUserInCompany must NOT be called for root callers
        verify(userRepo, never()).existsUserInCompany(USER_ID, COMPANY_A);
    }

    @Test
    void getByUid_root_resolvesAnotherRootUser() {
        // A root caller can look up a root target without restriction.
        AppUser rootTarget = stubUser(99L, "uid-root", "rootadmin");
        when(rootTarget.isRoot()).thenReturn(true);
        when(userRepo.findByUid("uid-root")).thenReturn(Optional.of(rootTarget));
        RequestContext.set(root());

        UserDto result = service.getByUid("uid-root");

        assertThat(result.uid()).isEqualTo("uid-root");
        verify(userRepo, never()).existsUserInCompany(99L, COMPANY_A);
    }

    @Test
    void getByUid_nonRoot_rootTarget_throws404() {
        // A non-root caller resolving a root user must receive 404 — do not leak existence.
        AppUser rootTarget = stubUser(99L, "uid-root", "rootadmin");
        when(rootTarget.isRoot()).thenReturn(true);
        when(userRepo.findByUid("uid-root")).thenReturn(Optional.of(rootTarget));
        RequestContext.set(nonRoot(COMPANY_A));

        assertThatThrownBy(() -> service.getByUid("uid-root"))
                .isInstanceOf(NotFoundException.class);
        // company check must NOT be reached — root guard fires first
        verify(userRepo, never()).existsUserInCompany(99L, COMPANY_A);
    }

    @Test
    void getByUid_nullPrincipal_rootTarget_throws404() {
        // Null principal is also treated as non-root for the root-visibility guard.
        AppUser rootTarget = stubUser(99L, "uid-root", "rootadmin");
        when(rootTarget.isRoot()).thenReturn(true);
        when(userRepo.findByUid("uid-root")).thenReturn(Optional.of(rootTarget));
        // No RequestContext set → principal is null.

        assertThatThrownBy(() -> service.getByUid("uid-root"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getByUid_sameCompany_resolves() {
        AppUser user = stubUser(USER_ID, USER_UID, "alice");
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        when(userRepo.existsUserInCompany(USER_ID, COMPANY_A)).thenReturn(true);
        RequestContext.set(nonRoot(COMPANY_A));

        UserDto result = service.getByUid(USER_UID);

        assertThat(result.uid()).isEqualTo(USER_UID);
    }

    @Test
    void getByUid_outOfCompany_throws404() {
        AppUser user = stubUser(USER_ID, USER_UID, "alice");
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        // user belongs to company B, caller is in company A
        when(userRepo.existsUserInCompany(USER_ID, COMPANY_A)).thenReturn(false);
        RequestContext.set(nonRoot(COMPANY_A));

        assertThatThrownBy(() -> service.getByUid(USER_UID))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void getByUid_nonRoot_nullCompany_throws404() {
        AppUser user = stubUser(USER_ID, USER_UID, "alice");
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        RequestContext.set(nonRoot(null));

        assertThatThrownBy(() -> service.getByUid(USER_UID))
                .isInstanceOf(NotFoundException.class);
    }
}
