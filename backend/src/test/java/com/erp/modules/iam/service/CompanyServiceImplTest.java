package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.fx.repository.CompanyCurrencyRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.iam.domain.dto.CompanyDto;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.bootstrap.CompanyProvisioningService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CompanyServiceImpl#listAccessibleByOrganisationUid}.
 *
 * <p>Covers the additive oracle introduced in V77: a company is visible when the caller has access
 * via an active role grant OR a branch assignment OR an explicit user_company membership — any one
 * path is sufficient. Also validates root bypass and the null/empty-principal fail-closed path.
 */
class CompanyServiceImplTest {

    private static final String ORG_UID    = "org-uid-001";
    private static final Long   ORG_ID     = 1L;
    private static final Long   COMPANY_A  = 10L;
    private static final Long   COMPANY_B  = 20L;
    private static final Long   COMPANY_C  = 30L;

    private CompanyRepository     companyRepo;
    private OrganisationRepository orgRepo;
    private UserRoleRepository    userRoleRepo;
    private UserBranchRepository  userBranchRepo;
    private UserCompanyRepository userCompanyRepo;

    private CompanyServiceImpl service;
    private Organisation       org;

    @BeforeEach
    void setUp() {
        companyRepo     = mock(CompanyRepository.class);
        orgRepo         = mock(OrganisationRepository.class);
        userRoleRepo    = mock(UserRoleRepository.class);
        userBranchRepo  = mock(UserBranchRepository.class);
        userCompanyRepo = mock(UserCompanyRepository.class);

        service = new CompanyServiceImpl(
                companyRepo,
                orgRepo,
                mock(JournalEntryRepository.class),
                mock(CurrencyRepository.class),
                mock(CompanyCurrencyRepository.class),
                mock(AuditService.class),
                userRoleRepo,
                userBranchRepo,
                userCompanyRepo,
                mock(CompanyProvisioningService.class));

        org = mock(Organisation.class);
        when(org.getId()).thenReturn(ORG_ID);
        when(orgRepo.findByUid(ORG_UID)).thenReturn(Optional.of(org));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Company stubCompany(Long id, String uid) {
        Company c = mock(Company.class);
        when(c.getId()).thenReturn(id);
        when(c.getUid()).thenReturn(uid);
        when(c.getOrganisation()).thenReturn(org);
        when(c.getCode()).thenReturn("CO-" + id);
        when(c.getName()).thenReturn("Company " + id);
        when(c.getStatus()).thenReturn(MasterStatus.ACTIVE);
        return c;
    }

    private UserRole stubUserRole(Long companyId) {
        UserRole ur = mock(UserRole.class);
        when(ur.getCompanyId()).thenReturn(companyId);
        return ur;
    }

    private void stubNoAccess(Long userId) {
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());
        when(userBranchRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());
        when(userCompanyRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /** (1) Non-root with a role grant in A only — sees only A. */
    @Test
    void nonRoot_roleGrantInA_seesOnlyA() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        Long userId = 42L;
        // Build the stub BEFORE passing to thenReturn — avoids the Mockito nested-when trap.
        UserRole grantInA = stubUserRole(COMPANY_A);
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(grantInA));
        when(userBranchRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());
        when(userCompanyRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());

        RequestContext.set(new RequestContext.Principal(userId, "alice@test.com", false, COMPANY_A, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
    }

    /** (2) Root sees ALL companies regardless of grants. */
    @Test
    void root_seesAllCompanies() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        RequestContext.set(new RequestContext.Principal(1L, "root@test.com", true, null, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CompanyDto::uid).containsExactlyInAnyOrder("uid-a", "uid-b");
    }

    /** (3) Non-root with no grants at all sees an empty list. */
    @Test
    void nonRoot_noGrants_seesEmpty() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID)).thenReturn(List.of(companyA));

        Long userId = 99L;
        stubNoAccess(userId);

        RequestContext.set(new RequestContext.Principal(userId, "bob@test.com", false, COMPANY_A, null, null));

        assertThat(service.listAccessibleByOrganisationUid(ORG_UID)).isEmpty();
    }

    /** (4) V77 additive: user_company-only member (no role, no branch) sees that company. */
    @Test
    void nonRoot_userCompanyOnlyMember_seesCompany() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        Long userId = 55L;
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());
        when(userBranchRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());
        // explicit user_company membership in A only
        when(userCompanyRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of(COMPANY_A));

        RequestContext.set(new RequestContext.Principal(userId, "carol@test.com", false, COMPANY_A, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
    }

    /** (5) V77 additive: branch-only member (no role, no explicit user_company) sees company via branch. */
    @Test
    void nonRoot_branchOnlyMember_seesCompanyViaBranch() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        Long userId = 66L;
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());
        when(userBranchRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of(COMPANY_A));
        when(userCompanyRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());

        RequestContext.set(new RequestContext.Principal(userId, "dave@test.com", false, COMPANY_A, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
    }

    /** (6) Additive union: three separate paths across three companies — user sees all three. */
    @Test
    void nonRoot_allThreePaths_seesUnion() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        Company companyC = stubCompany(COMPANY_C, "uid-c");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB, companyC));

        Long userId = 77L;
        // Build stub before thenReturn — avoids the Mockito nested-when trap.
        UserRole grantInA = stubUserRole(COMPANY_A);
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(grantInA));
        when(userBranchRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of(COMPANY_B));
        when(userCompanyRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of(COMPANY_C));

        RequestContext.set(new RequestContext.Principal(userId, "eve@test.com", false, COMPANY_A, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(CompanyDto::uid)
                .containsExactlyInAnyOrder("uid-a", "uid-b", "uid-c");
    }

    /** (7) Null principal (unauthenticated) returns empty list (fail-closed). */
    @Test
    void nullPrincipal_returnsEmpty() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID)).thenReturn(List.of(companyA));
        // no RequestContext set

        assertThat(service.listAccessibleByOrganisationUid(ORG_UID)).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Plain admin list (GET /api/v1/companies) — must be scoped exactly like the picker.
    // Regression guard for the cross-company enumeration leak fixed in the 2026-06-26 e2e audit:
    // listByOrganisationUid previously returned EVERY company in the org to any COMPANY.VIEW holder.
    // -------------------------------------------------------------------------

    /** (8) Non-root single-company holder must NOT see other companies via the plain list. */
    @Test
    void plainList_nonRoot_roleGrantInA_seesOnlyA() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        Long userId = 42L;
        UserRole grantInA = stubUserRole(COMPANY_A);
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of(grantInA));
        when(userBranchRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());
        when(userCompanyRepo.findActiveCompanyIdsByUserId(userId)).thenReturn(Set.of());

        RequestContext.set(new RequestContext.Principal(userId, "alice@test.com", false, COMPANY_A, null, null));

        List<CompanyDto> result = service.listByOrganisationUid(ORG_UID);

        assertThat(result).extracting(CompanyDto::uid).containsExactly("uid-a");
    }

    /** (9) Root still sees ALL companies via the plain list. */
    @Test
    void plainList_root_seesAllCompanies() {
        Company companyA = stubCompany(COMPANY_A, "uid-a");
        Company companyB = stubCompany(COMPANY_B, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        RequestContext.set(new RequestContext.Principal(1L, "root@test.com", true, null, null, null));

        assertThat(service.listByOrganisationUid(ORG_UID))
                .extracting(CompanyDto::uid).containsExactlyInAnyOrder("uid-a", "uid-b");
    }
}
