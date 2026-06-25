package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.AssignUserCompanyRequest;
import com.erp.modules.iam.domain.dto.UserCompanyDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserCompany;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link UserCompanyServiceImpl}.
 *
 * <p>Covers: ensureMembership idempotency; explicit assign / soft-revoke; listForUser
 * tenant-isolation (root sees all, non-root scoped, null-company fail-closed).
 */
class UserCompanyServiceImplTest {

    private static final Long   USER_ID    = 10L;
    private static final String USER_UID   = "user-uid-010";
    private static final Long   COMPANY_A  = 100L;
    private static final Long   COMPANY_B  = 200L;
    private static final String CO_UID_A   = "co-uid-a";
    private static final String CO_UID_B   = "co-uid-b";

    private UserCompanyRepository userCompanyRepo;
    private AppUserRepository     userRepo;
    private CompanyRepository     companyRepo;
    private ScopeGuard            scopeGuard;
    private AuditService          audit;

    private UserCompanyServiceImpl service;

    @BeforeEach
    void setUp() {
        userCompanyRepo = mock(UserCompanyRepository.class);
        userRepo        = mock(AppUserRepository.class);
        companyRepo     = mock(CompanyRepository.class);
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);

        service = new UserCompanyServiceImpl(
                userCompanyRepo, userRepo, companyRepo, scopeGuard, audit);

        // default: target user exists
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(USER_ID);
        when(user.getUid()).thenReturn(USER_UID);
        when(userRepo.findByUid(USER_UID)).thenReturn(Optional.of(user));
        when(userRepo.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // ensureMembership
    // -------------------------------------------------------------------------

    @Test
    void ensureMembership_noExistingRow_savesNewRow() {
        Company company = stubCompany(COMPANY_A, CO_UID_A);
        when(companyRepo.findById(COMPANY_A)).thenReturn(Optional.of(company));
        when(userCompanyRepo.existsByUserIdAndCompanyIdAndRevokedAtIsNull(USER_ID, COMPANY_A))
                .thenReturn(false);

        service.ensureMembership(USER_ID, COMPANY_A, 99L);

        verify(userCompanyRepo).save(any(UserCompany.class));
    }

    @Test
    void ensureMembership_activeRowExists_isNoOp() {
        when(userCompanyRepo.existsByUserIdAndCompanyIdAndRevokedAtIsNull(USER_ID, COMPANY_A))
                .thenReturn(true);

        service.ensureMembership(USER_ID, COMPANY_A, 99L);

        verify(userCompanyRepo, never()).save(any());
    }

    @Test
    void ensureMembership_companyNotFound_doesNotThrow() {
        when(userCompanyRepo.existsByUserIdAndCompanyIdAndRevokedAtIsNull(USER_ID, COMPANY_A))
                .thenReturn(false);
        when(companyRepo.findById(COMPANY_A)).thenReturn(Optional.empty());

        // must not throw — exception-free hook
        service.ensureMembership(USER_ID, COMPANY_A, null);

        verify(userCompanyRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // assign
    // -------------------------------------------------------------------------

    @Test
    void assign_newMembership_returnsDtoAndSaves() {
        Company company = stubCompany(COMPANY_A, CO_UID_A);
        when(companyRepo.findByUid(CO_UID_A)).thenReturn(Optional.of(company));
        when(userCompanyRepo.existsByUserIdAndCompanyIdAndRevokedAtIsNull(USER_ID, COMPANY_A))
                .thenReturn(false);

        UserCompany saved = stubSavedUserCompany(1L, "uc-uid-1", USER_ID, company);
        when(userCompanyRepo.save(any())).thenReturn(saved);

        RequestContext.set(new RequestContext.Principal(99L, "admin", false, COMPANY_A, null, null));

        UserCompanyDto dto = service.assign(new AssignUserCompanyRequest(USER_UID, CO_UID_A));

        assertThat(dto.uid()).isEqualTo("uc-uid-1");
        assertThat(dto.userUid()).isEqualTo(USER_UID);
        assertThat(dto.companyUid()).isEqualTo(CO_UID_A);
    }

    @Test
    void assign_duplicateActiveMembership_throwsConflict() {
        Company company = stubCompany(COMPANY_A, CO_UID_A);
        when(companyRepo.findByUid(CO_UID_A)).thenReturn(Optional.of(company));
        when(userCompanyRepo.existsByUserIdAndCompanyIdAndRevokedAtIsNull(USER_ID, COMPANY_A))
                .thenReturn(true);

        RequestContext.set(new RequestContext.Principal(99L, "admin", false, COMPANY_A, null, null));

        assertThatThrownBy(() -> service.assign(new AssignUserCompanyRequest(USER_UID, CO_UID_A)))
                .isInstanceOf(ConflictException.class);
        verify(userCompanyRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // remove (soft-revoke)
    // -------------------------------------------------------------------------

    @Test
    void remove_activeMembership_setsRevokedAt() {
        Company company = stubCompany(COMPANY_A, CO_UID_A);
        UserCompany uc = mock(UserCompany.class);
        when(uc.getId()).thenReturn(1L);
        when(uc.getUid()).thenReturn("uc-uid-1");
        when(uc.isActive()).thenReturn(true);
        when(uc.getCompany()).thenReturn(company);

        when(userCompanyRepo.findByUid("uc-uid-1")).thenReturn(Optional.of(uc));

        RequestContext.set(new RequestContext.Principal(99L, "admin", false, COMPANY_A, null, null));

        service.remove("uc-uid-1");

        verify(uc).revoke(any());
    }

    @Test
    void remove_alreadyRevoked_throwsConflict() {
        Company company = stubCompany(COMPANY_A, CO_UID_A);
        UserCompany uc = mock(UserCompany.class);
        when(uc.isActive()).thenReturn(false);
        when(uc.getCompany()).thenReturn(company);
        when(userCompanyRepo.findByUid("uc-uid-1")).thenReturn(Optional.of(uc));

        RequestContext.set(new RequestContext.Principal(99L, "admin", false, COMPANY_A, null, null));

        assertThatThrownBy(() -> service.remove("uc-uid-1"))
                .isInstanceOf(ConflictException.class);
    }

    // -------------------------------------------------------------------------
    // listForUser — tenant-isolation
    // -------------------------------------------------------------------------

    @Test
    void listForUser_root_seesAllMemberships() {
        UserCompany ucA = stubActiveUserCompany(1L, "uc-a", USER_ID, stubCompany(COMPANY_A, CO_UID_A));
        UserCompany ucB = stubActiveUserCompany(2L, "uc-b", USER_ID, stubCompany(COMPANY_B, CO_UID_B));
        when(userCompanyRepo.findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(USER_ID))
                .thenReturn(List.of(ucA, ucB));

        RequestContext.set(new RequestContext.Principal(1L, "root", true, null, null, null));

        List<UserCompanyDto> result = service.listForUser(USER_UID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(UserCompanyDto::uid)
                .containsExactlyInAnyOrder("uc-a", "uc-b");
    }

    @Test
    void listForUser_nonRoot_seesOnlyActiveCompanyMembership() {
        UserCompany ucA = stubActiveUserCompany(1L, "uc-a", USER_ID, stubCompany(COMPANY_A, CO_UID_A));
        UserCompany ucB = stubActiveUserCompany(2L, "uc-b", USER_ID, stubCompany(COMPANY_B, CO_UID_B));
        when(userCompanyRepo.findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(USER_ID))
                .thenReturn(List.of(ucA, ucB));

        RequestContext.set(new RequestContext.Principal(99L, "admin", false, COMPANY_A, null, null));

        List<UserCompanyDto> result = service.listForUser(USER_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uc-a");
    }

    @Test
    void listForUser_nonRoot_nullActiveCompany_returnsEmpty() {
        RequestContext.set(new RequestContext.Principal(99L, "admin", false, null, null, null));

        List<UserCompanyDto> result = service.listForUser(USER_UID);

        assertThat(result).isEmpty();
        verify(userCompanyRepo, never()).findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Company stubCompany(Long id, String uid) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getUid()).thenReturn(uid);
        when(c.getName()).thenReturn("Company-" + id);
        return c;
    }

    private UserCompany stubSavedUserCompany(Long id, String uid, Long userId, Company company) {
        UserCompany uc = mock(UserCompany.class);
        when(uc.getId()).thenReturn(id);
        when(uc.getUid()).thenReturn(uid);
        when(uc.getUserId()).thenReturn(userId);
        when(uc.getCompany()).thenReturn(company);
        when(uc.getAssignedAt()).thenReturn(java.time.Instant.EPOCH);
        when(uc.getRevokedAt()).thenReturn(null);
        when(uc.isActive()).thenReturn(true);
        return uc;
    }

    private UserCompany stubActiveUserCompany(Long id, String uid, Long userId, Company company) {
        return stubSavedUserCompany(id, uid, userId, company);
    }
}
