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
 * Unit tests for the tenant-isolation fixes in {@link UserServiceImpl}:
 * <ul>
 *   <li>{@link UserServiceImpl#list()} — company-scoped for non-root; org-wide for root; fail-closed
 *       when companyId is null.
 *   <li>{@link UserServiceImpl#listOrgWide()} — always returns the full org list.
 *   <li>{@link UserServiceImpl#getByUid(String)} — 404 for out-of-company target (non-root); OK for
 *       same-company and root.
 * </ul>
 *
 * <p>Style: mirrors {@link CompanyServiceImplTest} (mock repos + RequestContext.set/clear).
 * Security audit 2026-06-25.
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
        AppUser inA  = stubUser(1L, "uid-a", "alice");
        AppUser inB  = stubUser(2L, "uid-b", "bob");

        when(userRepo.findAllInCompanyOrderByUsername(COMPANY_A)).thenReturn(List.of(inA));
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
        verify(userRepo, never()).findAllByOrderByUsername();
        // confirm bob (company B) is absent
        assertThat(result).noneMatch(d -> d.uid().equals("uid-b"));
    }

    @Test
    void list_nonRoot_multiCompanyUser_includedViaCompanyAQuery() {
        AppUser multi = stubUser(3L, "uid-multi", "charlie");

        when(userRepo.findAllInCompanyOrderByUsername(COMPANY_A)).thenReturn(List.of(multi));
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.list();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-multi");
    }

    @Test
    void list_root_seesAllOrgWide() {
        AppUser u1 = stubUser(1L, "uid-a", "alice");
        AppUser u2 = stubUser(2L, "uid-b", "bob");

        when(userRepo.findAllByOrderByUsername()).thenReturn(List.of(u1, u2));
        RequestContext.set(root());

        List<UserDto> result = service.list();

        assertThat(result).hasSize(2);
        verify(userRepo, never()).findAllInCompanyOrderByUsername(COMPANY_A);
    }

    @Test
    void list_nonRoot_nullCompany_returnsEmpty_failClosed() {
        RequestContext.set(nonRoot(null));

        List<UserDto> result = service.list();

        assertThat(result).isEmpty();
        verify(userRepo, never()).findAllByOrderByUsername();
        verify(userRepo, never()).findAllInCompanyOrderByUsername(null);
    }

    @Test
    void list_nullPrincipal_treatedAsRoot_seesAll() {
        AppUser u1 = stubUser(1L, "uid-a", "alice");
        when(userRepo.findAllByOrderByUsername()).thenReturn(List.of(u1));
        // No RequestContext set → principal is null

        List<UserDto> result = service.list();

        assertThat(result).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // listOrgWide()
    // -----------------------------------------------------------------------

    @Test
    void listOrgWide_alwaysReturnsFullOrgList() {
        AppUser u1 = stubUser(1L, "uid-a", "alice");
        AppUser u2 = stubUser(2L, "uid-b", "bob");
        when(userRepo.findAllByOrderByUsername()).thenReturn(List.of(u1, u2));
        // non-root principal — must still return all
        RequestContext.set(nonRoot(COMPANY_A));

        List<UserDto> result = service.listOrgWide();

        assertThat(result).hasSize(2);
    }

    // -----------------------------------------------------------------------
    // getByUid() — tenant-isolation guard
    // -----------------------------------------------------------------------

    @Test
    void getByUid_root_alwaysResolves() {
        AppUser user = stubUser(USER_ID, USER_UID, "alice");
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        RequestContext.set(root());

        UserDto result = service.getByUid(USER_UID);

        assertThat(result.uid()).isEqualTo(USER_UID);
        // existsUserInCompany must NOT be called for root
        verify(userRepo, never()).existsUserInCompany(USER_ID, COMPANY_A);
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
