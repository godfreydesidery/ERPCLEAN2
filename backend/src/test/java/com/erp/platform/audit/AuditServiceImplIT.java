package com.erp.platform.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.AssignBranchRequest;
import com.erp.modules.iam.domain.dto.CreateUserRequest;
import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.SetPasswordRequest;
import com.erp.modules.iam.domain.dto.UserBranchDto;
import com.erp.modules.iam.domain.dto.UserDto;
import com.erp.modules.iam.domain.dto.UserRoleDto;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.UserBranchService;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.modules.iam.service.UserService;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for the Slice 6 audit trail at the service layer (ADR-0004).
 *
 * <p>Verifies:
 * <ol>
 *   <li>Correct {@code AuditActions} constant, actorUserId, targetType, targetId, targetUid and
 *       non-null {@code at} for each emitting service method.
 *   <li>Same-tx rollback guarantee: a {@link TransactionTemplate}-wrapped call that emits USER.CREATE
 *       then throws rolls back both the user row and the audit row atomically.
 *   <li>MANDATORY propagation: calling {@link AuditService#record} outside any transaction throws
 *       immediately (proves it never silently skips an audit row outside a tx).
 *   <li>ROOT.BYPASS: root acting cross-company emits the bypass row; same-company does not.
 * </ol>
 *
 * <p>A ROOT {@link RequestContext.Principal} is set in {@link #setUp} (all service calls need the
 * ThreadLocal to be populated so ScopeGuard passes). It is cleared in {@link #tearDown}.
 */
class AuditServiceImplIT extends PostgresIntegrationTest {

    // --- services under audit ---
    @Autowired private UserService userService;
    @Autowired private UserRoleService userRoleService;
    @Autowired private UserBranchService userBranchService;

    // --- audit read-back ---
    @Autowired private AuditRepository auditRepo;
    @Autowired private AuditService auditService;

    // --- seeding ---
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private UserBranchRepository userBranches;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    // --- for same-tx rollback test ---
    @Autowired private TransactionTemplate txTemplate;

    private static final String PASSWORD = "TestPass1";

    private AppUser rootUser;
    private AppUser targetUser;
    private Company companyA;
    private Company companyB;
    private Branch branchInA;
    private Branch branchInB;
    private Role testRole;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Audit Test Org"));
        companyA = companies.save(new Company(org, "ATA", "Audit Alpha"));
        companyB = companies.save(new Company(org, "ATB", "Audit Beta"));

        branchInA = new Branch(companyA, "AT-A1", "Audit Alpha HQ");
        branchInA.setDefault(true);
        branchInA = branches.save(branchInA);

        branchInB = new Branch(companyB, "AT-B1", "Audit Beta HQ");
        branchInB.setDefault(true);
        branchInB = branches.save(branchInB);

        rootUser = new AppUser("at_root", passwordEncoder.encode(PASSWORD), "Audit Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);

        UserBranch rootAssign = new UserBranch(rootUser.getId(), branchInA, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        targetUser = users.save(new AppUser("at_target", passwordEncoder.encode(PASSWORD), "Audit Target"));

        Permission perm = permissions.findByCode("COMPANY.MANAGE")
                .orElseThrow(() -> new IllegalStateException("COMPANY.MANAGE not seeded"));
        testRole = new Role("AUDIT_TEST_ROLE", "Audit Test Role");
        testRole.setPermissions(Set.of(perm));
        testRole = roles.save(testRole);

        // Root principal active in company A — all service calls pass ScopeGuard from here.
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyA.getId(), branchInA.getId(), "10.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // USER.CREATE
    // =========================================================================

    @Test
    void userCreate_emitsExactlyOneRow_withCorrectFields() {
        UserDto created = userService.create(
                new CreateUserRequest("audit_newuser", "Audit New User", PASSWORD, null, null));

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).as("exactly one audit row after USER.CREATE").hasSize(1);

        AuditLog row = rows.get(0);
        assertThat(row.getAction()).isEqualTo(AuditActions.USER_CREATE);
        assertThat(row.getTargetType()).isEqualTo("app_user");
        assertThat(row.getTargetUid()).isEqualTo(created.uid());
        assertThat(row.getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(row.getAt()).isNotNull();
    }

    // =========================================================================
    // USER.DISABLE
    // =========================================================================

    @Test
    void userDisable_emitsExactlyOneDisableRow() {
        UserDto created = userService.create(
                new CreateUserRequest("audit_disable_u", "Disable Me", PASSWORD, null, null));
        // CREATE row is already there; clear so we only count the DISABLE row.
        auditRepo.deleteAll();

        userService.disableByUid(created.uid());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditActions.USER_DISABLE);
        assertThat(rows.get(0).getTargetUid()).isEqualTo(created.uid());
        assertThat(rows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(rows.get(0).getAt()).isNotNull();
    }

    // =========================================================================
    // USER.UNLOCK
    // =========================================================================

    @Test
    void userUnlock_emitsExactlyOneUnlockRow() {
        UserDto created = userService.create(
                new CreateUserRequest("audit_unlock_u", "Unlock Me", PASSWORD, null, null));
        // Force-lock the account directly via the entity.
        AppUser user = users.findByUid(created.uid()).orElseThrow();
        for (int i = 0; i < 5; i++) {
            user.registerFailedLogin(5, 30, Instant.now());
        }
        users.saveAndFlush(user);
        auditRepo.deleteAll();

        userService.unlockByUid(created.uid());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditActions.USER_UNLOCK);
        assertThat(rows.get(0).getTargetUid()).isEqualTo(created.uid());
        assertThat(rows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(rows.get(0).getAt()).isNotNull();
    }

    // =========================================================================
    // USER.PASSWORD_SET
    // =========================================================================

    @Test
    void setPassword_emitsExactlyOnePasswordSetRow() {
        UserDto created = userService.create(
                new CreateUserRequest("audit_pwset_u", "PwSet Me", PASSWORD, null, null));
        auditRepo.deleteAll();

        userService.setPasswordByUid(created.uid(), new SetPasswordRequest("NewSecure9"));

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditActions.USER_PASSWORD_SET);
        assertThat(rows.get(0).getTargetUid()).isEqualTo(created.uid());
        assertThat(rows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(rows.get(0).getAt()).isNotNull();
        // Password or hash must NEVER appear in detail.
        assertThat(rows.get(0).getDetail()).as("password detail must be absent").isNull();
    }

    // =========================================================================
    // ROLE.GRANT
    // =========================================================================

    @Test
    void roleGrant_emitsExactlyOneGrantRow() {
        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));

        List<AuditLog> rows = auditRepo.findAll();
        // ScopeGuard.assertCanActIn: root acting IN its own company must NOT emit ROOT.BYPASS.
        // So exactly one row: the ROLE.GRANT.
        List<AuditLog> grantRows = rows.stream()
                .filter(r -> AuditActions.ROLE_GRANT.equals(r.getAction()))
                .toList();
        assertThat(grantRows).hasSize(1);
        assertThat(grantRows.get(0).getTargetUid()).isEqualTo(dto.uid());
        assertThat(grantRows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(grantRows.get(0).getAt()).isNotNull();

        // No ROOT.BYPASS row when root acts in its own company.
        assertThat(rows.stream().anyMatch(r -> AuditActions.ROOT_BYPASS.equals(r.getAction())))
                .as("no ROOT.BYPASS when root acts within own company")
                .isFalse();
    }

    // =========================================================================
    // ROLE.REVOKE
    // =========================================================================

    @Test
    void roleRevoke_emitsExactlyOneRevokeRow() {
        UserRoleDto dto = userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));
        auditRepo.deleteAll();

        userRoleService.revoke(dto.uid());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditActions.ROLE_REVOKE);
        assertThat(rows.get(0).getTargetUid()).isEqualTo(dto.uid());
        assertThat(rows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(rows.get(0).getAt()).isNotNull();
    }

    // =========================================================================
    // BRANCH.ASSIGN
    // =========================================================================

    @Test
    void branchAssign_emitsExactlyOneAssignRow() {
        UserBranchDto dto = userBranchService.assign(
                new AssignBranchRequest(targetUser.getUid(), branchInA.getUid(), true));

        List<AuditLog> rows = auditRepo.findAll();
        List<AuditLog> assignRows = rows.stream()
                .filter(r -> AuditActions.BRANCH_ASSIGN.equals(r.getAction()))
                .toList();
        assertThat(assignRows).hasSize(1);
        assertThat(assignRows.get(0).getTargetUid()).isEqualTo(dto.uid());
        assertThat(assignRows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(assignRows.get(0).getAt()).isNotNull();
    }

    // =========================================================================
    // BRANCH.SET_DEFAULT
    // =========================================================================

    @Test
    void branchSetDefault_emitsExactlyOneSetDefaultRow() {
        userBranchService.assign(new AssignBranchRequest(targetUser.getUid(), branchInA.getUid(), true));
        // Assign a second branch so we can set it as the new default.
        userBranchService.assign(new AssignBranchRequest(targetUser.getUid(), branchInB.getUid(), false));
        auditRepo.deleteAll();

        // Switch the existing branchInB assignment to be the default.
        List<UserBranchDto> list = userBranchService.listForUser(targetUser.getUid());
        UserBranchDto branchBAssign = list.stream()
                .filter(d -> branchInB.getUid().equals(d.branchUid()))
                .findFirst().orElseThrow();
        // Need to set root context to branchInB's company so ScopeGuard passes.
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyB.getId(), branchInB.getId(), "10.0.0.1"));
        userBranchService.setDefault(branchBAssign.uid());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditActions.BRANCH_SET_DEFAULT);
        assertThat(rows.get(0).getTargetUid()).isEqualTo(branchBAssign.uid());
        assertThat(rows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(rows.get(0).getAt()).isNotNull();
    }

    // =========================================================================
    // BRANCH.UNASSIGN
    // =========================================================================

    @Test
    void branchUnassign_emitsExactlyOneUnassignRow() {
        UserBranchDto dto = userBranchService.assign(
                new AssignBranchRequest(targetUser.getUid(), branchInA.getUid(), true));
        auditRepo.deleteAll();

        userBranchService.remove(dto.uid());

        List<AuditLog> rows = auditRepo.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo(AuditActions.BRANCH_UNASSIGN);
        assertThat(rows.get(0).getTargetUid()).isEqualTo(dto.uid());
        assertThat(rows.get(0).getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(rows.get(0).getAt()).isNotNull();
    }

    // =========================================================================
    // SAME-TX ROLLBACK — the money test
    //
    // A TransactionTemplate wraps: (1) userService.create -> emits USER.CREATE row, (2) throws
    // RuntimeException.  The whole tx rolls back: both the app_user row AND the audit_log row
    // must be absent afterward.  This proves the MANDATORY audit INSERT is not in a sub-tx of its
    // own — it participates in the caller's tx and rolls back with it.
    // =========================================================================

    @Test
    void userCreate_thenRollback_auditRowAlsoRolledBack() {
        assertThatThrownBy(() ->
                txTemplate.execute(status -> {
                    userService.create(new CreateUserRequest(
                            "rollback_user", "Rollback User", PASSWORD, null, null));
                    // Force rollback AFTER the audit.record(USER.CREATE) call inside create().
                    throw new RuntimeException("forced rollback");
                })
        ).isInstanceOf(RuntimeException.class);

        // Both the user row and its audit row must have been rolled back.
        assertThat(users.findByUsername("rollback_user"))
                .as("user must not exist after rollback")
                .isEmpty();
        assertThat(auditRepo.findAll())
                .as("audit row must also have been rolled back with the business row")
                .isEmpty();
    }

    // =========================================================================
    // MANDATORY propagation assertion
    //
    // Calling auditService.record() outside any @Transactional context must throw immediately.
    // This proves the service will never silently write an orphan audit row outside a transaction.
    // =========================================================================

    @Test
    void auditRecord_outsideTransaction_throws() {
        // RequestContext is set (setUp) but there is intentionally NO active Spring transaction.
        // MANDATORY propagation must cause an immediate exception — not a silent no-op.
        AuditEvent event = AuditEvent.of(AuditActions.USER_CREATE, "app_user", 1L, "FAKE_UID");

        assertThatThrownBy(() -> auditService.record(event))
                .as("record() outside a transaction must throw due to MANDATORY propagation")
                .isInstanceOf(Exception.class); // TransactionSystemException or IllegalTransactionStateException
    }

    // =========================================================================
    // ROOT.BYPASS — cross-company action emits the bypass row
    // =========================================================================

    @Test
    void rootBypass_crossCompanyGrant_emitsBypassRow() {
        // Root's active company is A. Granting in company B is cross-company — ROOT.BYPASS must fire.
        userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyB.getUid(), null));

        List<AuditLog> rows = auditRepo.findAll();

        // Exactly one ROOT.BYPASS row.
        List<AuditLog> bypassRows = rows.stream()
                .filter(r -> AuditActions.ROOT_BYPASS.equals(r.getAction()))
                .toList();
        assertThat(bypassRows)
                .as("ROOT.BYPASS must be emitted when root acts cross-company")
                .hasSize(1);

        AuditLog bypass = bypassRows.get(0);
        assertThat(bypass.getTargetType()).isEqualTo("company");
        assertThat(bypass.getTargetId()).isEqualTo(companyB.getId());
        assertThat(bypass.getActorUserId()).isEqualTo(rootUser.getId());
        assertThat(bypass.getAt()).isNotNull();

        // The normal action row (ROLE.GRANT) is also present.
        assertThat(rows.stream().anyMatch(r -> AuditActions.ROLE_GRANT.equals(r.getAction())))
                .as("ROLE.GRANT row must also be present")
                .isTrue();
    }

    @Test
    void rootBypass_sameCompanyGrant_doesNotEmitBypassRow() {
        // Root acts IN its own active company — no bypass row expected.
        userRoleService.grant(new GrantRoleRequest(
                targetUser.getUid(), testRole.getUid(), companyA.getUid(), null));

        boolean hasBypass = auditRepo.findAll().stream()
                .anyMatch(r -> AuditActions.ROOT_BYPASS.equals(r.getAction()));
        assertThat(hasBypass)
                .as("ROOT.BYPASS must NOT be emitted when root acts within its own company")
                .isFalse();
    }
}
