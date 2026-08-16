package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link AgentServiceImpl} against real Postgres (Testcontainers).
 *
 * <p>Covers BR-PARTY-10/11:
 * <ul>
 *   <li>INTERNAL agent referencing an ACTIVE app_user -> created; appUserId stored.
 *   <li>INTERNAL referencing an INACTIVE (disabled) user -> rejected.
 *   <li>INTERNAL with no user reference -> rejected.
 *   <li>EXTERNAL with a user reference -> rejected.
 *   <li>EXTERNAL with no user reference -> accepted; code is AGNT-####.
 * </ul>
 */
class AgentServiceImplIT extends PostgresIntegrationTest {

    @Autowired private AgentService agentService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Organisation org;
    private Company company;
    private Branch branch;
    private Long rootId;

    /** An ACTIVE app_user suitable for INTERNAL agents. */
    private AppUser activeUser;
    /** An INACTIVE (disabled) app_user — linking an internal agent to this must be rejected. */
    private AppUser inactiveUser;

    private static final String PASS = "AgentPass1!";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org = organisations.save(new Organisation("Agent Test Org"));
        company = companies.save(new Company(org, "AGCO", "Agent Co"));
        branch = branches.save(new Branch(company, "AG-B1", "Agent Branch 1"));

        // Root user — used for ScopeGuard context
        AppUser root = new AppUser("ag_root", passwordEncoder.encode(PASS), "Agent Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root = users.save(root);
        rootId = root.getId();

        // Active user that an INTERNAL agent can reference.
        // Must have a user_branch in the test company (security fix finding 4: isActiveUserInCompany).
        activeUser = new AppUser("ag_active", passwordEncoder.encode(PASS), "Active Agent User");
        activeUser.setOrganisationId(org.getId());
        activeUser = users.save(activeUser);
        assertThat(activeUser.getStatus()).isEqualTo(MasterStatus.ACTIVE);
        userBranches.save(new UserBranch(activeUser.getId(), branch, rootId));

        // Inactive user — status set to INACTIVE directly so we bypass the UserService disable guard
        inactiveUser = new AppUser("ag_inactive", passwordEncoder.encode(PASS), "Inactive Agent User");
        inactiveUser.setStatus(MasterStatus.INACTIVE);
        inactiveUser.setOrganisationId(org.getId());
        inactiveUser = users.save(inactiveUser);

        RequestContext.set(new RequestContext.Principal(
                rootId, "ag_root", true, company.getId(), branch.getId(), null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // INTERNAL agent + ACTIVE user -> accepted; appUserId stored; code is AGNT-####
    // -----------------------------------------------------------------------

    @Test
    void create_internalAgent_withActiveUser_accepted_appUserIdStored() {
        CreateAgentRequest req = internalRequest("Active Internal Agent", activeUser.getId());

        AgentDto dto = agentService.create(req);

        assertThat(dto.code()).isEqualTo("AGNT-0001");
        assertThat(dto.agentKind()).isEqualTo(AgentKind.INTERNAL);
        assertThat(dto.appUserId()).isEqualTo(activeUser.getId());
        assertThat(dto.status()).isEqualTo(MasterStatus.ACTIVE);
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-10/11: INTERNAL + INACTIVE user -> rejected
    // -----------------------------------------------------------------------

    @Test
    void create_internalAgent_withInactiveUser_throwsIllegalArgument_BR_PARTY_10() {
        CreateAgentRequest req = internalRequest("Inactive Ref Agent", inactiveUser.getId());

        assertThatThrownBy(() -> agentService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active, belong to this company");
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-10: the ROOT super-admin cannot be an internal agent — even when
    // active and assigned to the company. Rejection is due to root-ness, not membership.
    // -----------------------------------------------------------------------

    @Test
    void create_internalAgent_referencingRootUser_throwsIllegalArgument_BR_PARTY_10() {
        // Give root a valid branch membership in the company so the ONLY disqualifier is root-ness.
        userBranches.save(new UserBranch(rootId, branch, rootId));

        CreateAgentRequest req = internalRequest("Root As Agent", rootId);

        assertThatThrownBy(() -> agentService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active, belong to this company");
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-10: INTERNAL with no user -> rejected
    // -----------------------------------------------------------------------

    @Test
    void create_internalAgent_withNoUser_throwsIllegalArgument_BR_PARTY_10() {
        CreateAgentRequest req = internalRequest("No User Internal", null);

        assertThatThrownBy(() -> agentService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be linked to a user account");
    }

    // -----------------------------------------------------------------------
    // BR-PARTY-11: EXTERNAL with a user reference -> rejected
    // -----------------------------------------------------------------------

    @Test
    void create_externalAgent_withUserReference_throwsIllegalArgument_BR_PARTY_11() {
        CreateAgentRequest req = externalRequest("External With User", activeUser.getId());

        assertThatThrownBy(() -> agentService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be linked to a user account");
    }

    // -----------------------------------------------------------------------
    // EXTERNAL with no user -> accepted; appUserId is null
    // -----------------------------------------------------------------------

    @Test
    void create_externalAgent_withNoUser_accepted_appUserIdIsNull() {
        CreateAgentRequest req = externalRequest("Pure External Agent", null);

        AgentDto dto = agentService.create(req);

        assertThat(dto.agentKind()).isEqualTo(AgentKind.EXTERNAL);
        assertThat(dto.appUserId()).isNull();
        assertThat(dto.code()).isEqualTo("AGNT-0001");
    }

    // -----------------------------------------------------------------------
    // INTERNAL referencing a non-existent user id -> rejected (isActiveUser returns false)
    // -----------------------------------------------------------------------

    @Test
    void create_internalAgent_withNonExistentUserId_throwsIllegalArgument() {
        CreateAgentRequest req = internalRequest("Ghost User Agent", Long.MAX_VALUE);

        assertThatThrownBy(() -> agentService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active, belong to this company");
    }

    // -----------------------------------------------------------------------
    // Security regression (finding 4): INTERNAL agent in company A referencing a user who
    // belongs ONLY to company B must be rejected (cross-tenant user reference).
    // -----------------------------------------------------------------------

    @Test
    void create_internalAgent_userBelongsOnlyToOtherCompany_throwsIllegalArgument_BR_PARTY_10() {
        // Seed company B with its own branch and a user assigned only there.
        Company companyB = companies.save(new Company(org, "AGCOB", "Agent Co B"));
        Branch branchB = branches.save(new Branch(companyB, "AG-B2", "Agent Branch B"));
        AppUser userInB = new AppUser("ag_user_b", passwordEncoder.encode(PASS), "User In B Only");
        userInB.setOrganisationId(org.getId());
        userInB = users.save(userInB);
        userBranches.save(new UserBranch(userInB.getId(), branchB, rootId));

        // Context is company (A) — attempt to create an INTERNAL agent referencing userInB.
        CreateAgentRequest crossTenantReq = internalRequest("Cross-Tenant Agent", userInB.getId());
        assertThatThrownBy(() -> agentService.create(crossTenantReq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active, belong to this company");
    }

    // -----------------------------------------------------------------------
    // Archive: status flips to ARCHIVED
    // -----------------------------------------------------------------------

    @Test
    void archiveByUid_setsStatusArchived() {
        AgentDto dto = agentService.create(externalRequest("Archive Me Agent", null));

        agentService.archiveByUid(dto.uid());

        AgentDto fetched = agentService.getByUid(dto.uid());
        assertThat(fetched.status()).isEqualTo(MasterStatus.ARCHIVED);
    }

    // -----------------------------------------------------------------------
    // P2 D5 — performance targets + country settable via create/update
    // -----------------------------------------------------------------------

    @Test
    void create_withD5Targets_persistsCountrySalesTargetAndQuota() {
        CreateAgentRequest req = new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Target Agent", null,
                null, false, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null,
                "TZ", new java.math.BigDecimal("50000.00"), new java.math.BigDecimal("60000.00"));

        AgentDto dto = agentService.create(req);

        assertThat(dto.country()).isEqualTo("TZ");
        assertThat(dto.salesTarget()).isEqualByComparingTo("50000.00");
        assertThat(dto.quota()).isEqualByComparingTo("60000.00");
    }

    @Test
    void update_withD5Targets_overwritesFields() {
        AgentDto created = agentService.create(externalRequest("Edit Agent", null));

        var updated = agentService.updateByUid(created.uid(),
                new com.erp.modules.parties.domain.dto.UpdateAgentRequest(
                        PartyType.INDIVIDUAL, "Edit Agent", null, null, false, null,
                        null, null, null, null, null, null, null, null,
                        AgentKind.EXTERNAL, null,
                        "KE", new java.math.BigDecimal("100.00"), new java.math.BigDecimal("200.00")));

        assertThat(updated.country()).isEqualTo("KE");
        assertThat(updated.salesTarget()).isEqualByComparingTo("100.00");
        assertThat(updated.quota()).isEqualByComparingTo("200.00");
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CreateAgentRequest internalRequest(String name, Long appUserId) {
        return new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, name, null,
                null, false, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, appUserId);
    }

    private CreateAgentRequest externalRequest(String name, Long appUserId) {
        return new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, name, null,
                null, false, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, appUserId);
    }
}
