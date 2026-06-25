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
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.bootstrap.CompanyProvisioningService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for the tenant-isolation fix in
 * {@link CompanyServiceImpl#listAccessibleByOrganisationUid}.
 *
 * <p>Security audit 2026-06-25: a non-root COMPANY.VIEW holder was incorrectly returned every
 * company in the org. The fix gates the "all companies" branch on {@code principal.root()} only.
 *
 * <p>Three assertions:
 * <ol>
 *   <li>Non-root user with a role grant ONLY in company A sees just company A — NOT company B.
 *   <li>Root principal sees ALL companies in the org.
 *   <li>Non-root user with NO active grant sees an empty list.
 * </ol>
 */
class CompanyServiceImplTest {

    private static final String ORG_UID = "org-uid-001";
    private static final Long   ORG_ID  = 1L;

    private CompanyRepository          companyRepo;
    private OrganisationRepository     orgRepo;
    private UserRoleRepository         userRoleRepo;

    private CompanyServiceImpl service;

    // Shared org stub
    private Organisation org;

    @BeforeEach
    void setUp() {
        companyRepo   = mock(CompanyRepository.class);
        orgRepo       = mock(OrganisationRepository.class);
        userRoleRepo  = mock(UserRoleRepository.class);

        service = new CompanyServiceImpl(
                companyRepo,
                orgRepo,
                mock(JournalEntryRepository.class),
                mock(CurrencyRepository.class),
                mock(CompanyCurrencyRepository.class),
                mock(AuditService.class),
                userRoleRepo,
                mock(CompanyProvisioningService.class));

        org = mock(Organisation.class);
        when(org.getId()).thenReturn(ORG_ID);
        when(orgRepo.findByUid(ORG_UID)).thenReturn(Optional.of(org));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Minimal Company stub that satisfies CompanyDto.from(). */
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

    /** Minimal UserRole stub with the given companyId and no revokedAt (active). */
    private UserRole stubUserRole(Long companyId) {
        UserRole ur = mock(UserRole.class);
        when(ur.getCompanyId()).thenReturn(companyId);
        return ur;
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * (1) Non-root user whose only active role grant is in company A (id=10) must NOT see company B
     * (id=20), even when their role carries COMPANY.VIEW for company A.
     */
    @Test
    void nonRootUser_withGrantInCompanyA_seesOnlyCompanyA() {
        Company companyA = stubCompany(10L, "uid-a");
        Company companyB = stubCompany(20L, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        Long userId = 42L;
        // Build the UserRole stub BEFORE passing it to thenReturn — avoids the nested-when trap.
        UserRole grantInA = stubUserRole(10L);
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId))
                .thenReturn(List.of(grantInA));

        // Non-root principal active in company A
        RequestContext.set(new RequestContext.Principal(userId, "alice@test.com", false, 10L, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).uid()).isEqualTo("uid-a");
    }

    /**
     * (2) Root principal sees ALL companies in the org regardless of role assignments.
     */
    @Test
    void rootPrincipal_seesAllCompaniesInOrg() {
        Company companyA = stubCompany(10L, "uid-a");
        Company companyB = stubCompany(20L, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        // Root principal — root() == true
        RequestContext.set(new RequestContext.Principal(1L, "root@test.com", true, null, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(CompanyDto::uid).containsExactlyInAnyOrder("uid-a", "uid-b");
    }

    /**
     * (3) Non-root user with NO active role grants sees an empty list.
     */
    @Test
    void nonRootUser_withNoGrant_seesEmptyList() {
        Company companyA = stubCompany(10L, "uid-a");
        Company companyB = stubCompany(20L, "uid-b");
        when(companyRepo.findByOrganisationIdOrderByName(ORG_ID))
                .thenReturn(List.of(companyA, companyB));

        Long userId = 99L;
        when(userRoleRepo.findByUserIdAndRevokedAtIsNull(userId)).thenReturn(List.of());

        RequestContext.set(new RequestContext.Principal(userId, "bob@test.com", false, 10L, null, null));

        List<CompanyDto> result = service.listAccessibleByOrganisationUid(ORG_UID);

        assertThat(result).isEmpty();
    }
}
