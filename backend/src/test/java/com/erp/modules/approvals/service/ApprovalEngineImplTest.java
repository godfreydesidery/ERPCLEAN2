package com.erp.modules.approvals.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.entity.ApprovalDecision;
import com.erp.modules.approvals.domain.entity.ApprovalRequest;
import com.erp.modules.approvals.domain.entity.ApprovalRequestStep;
import com.erp.modules.approvals.domain.enums.DecisionAction;
import com.erp.modules.approvals.repository.ApprovalDecisionRepository;
import com.erp.modules.approvals.repository.ApprovalPolicyStepRepository;
import com.erp.modules.approvals.repository.ApprovalRequestRepository;
import com.erp.modules.approvals.repository.ApprovalRequestStepRepository;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link ApprovalEngineImpl#toDto} read-time name/branch enrichment.
 *
 * <p>A top managers' complaint: the approvals inbox showed raw numeric {@code submittedBy} /
 * {@code resolvedBy} user ids, raw {@code approverRoleCode}s, and no branch name — so a GM /
 * procurement manager / branch manager could not tell at a glance whose request it was, which
 * branch it belonged to, or which role it was waiting on. Fixed by resolving
 * submittedByName/resolvedByName/branchName/branchCode/approverRoleName at read time against the
 * IAM {@code AppUserRepository}/{@code BranchRepository}/{@code RoleRepository} — mirrors
 * {@code SalesOrderServiceImpl.buildDto}'s customer/agent name enrichment.
 *
 * <p>Only {@code toDto} is exercised here (package-private, called directly); the rest of
 * {@link ApprovalEngineImpl} is covered by {@code ApprovalsEngineIT}.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalEngineImplTest {

    @Mock private ApprovalRequestRepository requestRepo;
    @Mock private ApprovalRequestStepRepository stepRepo;
    @Mock private ApprovalDecisionRepository decisionRepo;
    @Mock private ApprovalPolicyStepRepository policyStepRepo;
    @Mock private BranchRepository branchRepo;
    @Mock private AppUserRepository userRepo;
    @Mock private RoleRepository roleRepo;
    @Mock private ApprovalPolicyMatcher matcher;
    @Mock private ApprovalNumberGenerator numberGen;
    @Mock private ScopeGuard scopeGuard;
    @Mock private AuditService audit;
    @Mock private OutboxPublisher outbox;

    @InjectMocks private ApprovalEngineImpl engine;

    private static final Long COMPANY_ID   = 1L;
    private static final Long BRANCH_ID    = 10L;
    private static final Long SUBMITTER_ID = 100L;
    private static final Long RESOLVER_ID  = 200L;

    @Test
    void toDto_resolvesSubmitterResolverBranchAndStepRoleNames() {
        ApprovalRequest request = new ApprovalRequest(
                COMPANY_ID, BRANCH_ID, "PURCHASE_ORDER", "po-uid-1",
                new BigDecimal("5000000"), "TZS", null, null, "PO to Acme",
                SUBMITTER_ID, SUBMITTER_ID);
        ReflectionTestUtils.setField(request, "id", 500L);
        ReflectionTestUtils.setField(request, "uid", "APRUID000000000000000001");
        request.setResolvedBy(RESOLVER_ID);

        ApprovalRequestStep step = new ApprovalRequestStep(
                500L, COMPANY_ID, BRANCH_ID, (short) 1, "PURCHASING_MANAGER", SUBMITTER_ID);
        ReflectionTestUtils.setField(step, "id", 900L);
        ReflectionTestUtils.setField(step, "uid", "STEPUID0000000000000001");

        when(stepRepo.findByApprovalRequestIdOrderBySequence(500L)).thenReturn(List.of(step));
        when(decisionRepo.findByApprovalRequestStepIdOrderByDecidedAt(900L)).thenReturn(List.of());

        when(branchRepo.findById(BRANCH_ID)).thenReturn(Optional.of(branchOf("HQ", "Head Office")));

        when(userRepo.findById(SUBMITTER_ID))
                .thenReturn(Optional.of(userOf(SUBMITTER_ID, "buyer1", "Jane Buyer")));
        // Blank display name falls back to username.
        when(userRepo.findById(RESOLVER_ID))
                .thenReturn(Optional.of(userOf(RESOLVER_ID, "pm1", "")));

        // P4-1c (ADR-0062): the engine resolves role codes through findVisibleByCode, which is
        // scoped to the caller's tenant and returns a LIST — a derived findByCode returning Optional
        // would throw IncorrectResultSizeDataAccessException once two tenants share a code.
        when(roleRepo.findVisibleByCode(eq("PURCHASING_MANAGER"), any()))
                .thenReturn(List.of(new Role("PURCHASING_MANAGER", "Purchasing Manager")));

        ApprovalRequestDto dto = engine.toDto(request);

        assertThat(dto.submittedBy()).isEqualTo(SUBMITTER_ID);
        assertThat(dto.submittedByName()).isEqualTo("Jane Buyer");
        assertThat(dto.resolvedBy()).isEqualTo(RESOLVER_ID);
        assertThat(dto.resolvedByName()).isEqualTo("pm1");
        assertThat(dto.branchId()).isEqualTo(BRANCH_ID);
        assertThat(dto.branchName()).isEqualTo("Head Office");
        assertThat(dto.branchCode()).isEqualTo("HQ");

        assertThat(dto.steps()).hasSize(1);
        assertThat(dto.steps().get(0).approverRoleCode()).isEqualTo("PURCHASING_MANAGER");
        assertThat(dto.steps().get(0).approverRoleName()).isEqualTo("Purchasing Manager");
    }

    @Test
    void toDto_resolvesDecidedByNameOnDecisionHistory() {
        ApprovalRequest request = new ApprovalRequest(
                COMPANY_ID, BRANCH_ID, "PURCHASE_ORDER", "po-uid-3",
                new BigDecimal("3000000"), "TZS", null, null, "PO to Gamma",
                SUBMITTER_ID, SUBMITTER_ID);
        ReflectionTestUtils.setField(request, "id", 502L);
        ReflectionTestUtils.setField(request, "uid", "APRUID000000000000000003");
        request.setResolvedBy(RESOLVER_ID);

        ApprovalRequestStep step = new ApprovalRequestStep(
                502L, COMPANY_ID, BRANCH_ID, (short) 1, "PURCHASING_MANAGER", SUBMITTER_ID);
        ReflectionTestUtils.setField(step, "id", 902L);
        ReflectionTestUtils.setField(step, "uid", "STEPUID0000000000000003");

        ApprovalDecision decision = new ApprovalDecision(502L, 902L, COMPANY_ID, BRANCH_ID,
                DecisionAction.APPROVE, RESOLVER_ID, "Looks good", RESOLVER_ID);
        ReflectionTestUtils.setField(decision, "id", 950L);
        ReflectionTestUtils.setField(decision, "uid", "DECUID0000000000000001");

        when(stepRepo.findByApprovalRequestIdOrderBySequence(502L)).thenReturn(List.of(step));
        when(decisionRepo.findByApprovalRequestStepIdOrderByDecidedAt(902L))
                .thenReturn(List.of(decision));
        when(branchRepo.findById(BRANCH_ID)).thenReturn(Optional.of(branchOf("HQ", "Head Office")));
        when(userRepo.findById(SUBMITTER_ID))
                .thenReturn(Optional.of(userOf(SUBMITTER_ID, "buyer1", "Jane Buyer")));
        when(userRepo.findById(RESOLVER_ID))
                .thenReturn(Optional.of(userOf(RESOLVER_ID, "pm1", "Peter Manager")));
        // P4-1c (ADR-0062): the engine resolves role codes through findVisibleByCode, which is
        // scoped to the caller's tenant and returns a LIST — a derived findByCode returning Optional
        // would throw IncorrectResultSizeDataAccessException once two tenants share a code.
        when(roleRepo.findVisibleByCode(eq("PURCHASING_MANAGER"), any()))
                .thenReturn(List.of(new Role("PURCHASING_MANAGER", "Purchasing Manager")));

        ApprovalRequestDto dto = engine.toDto(request);

        assertThat(dto.steps()).hasSize(1);
        assertThat(dto.steps().get(0).decisions()).hasSize(1);
        assertThat(dto.steps().get(0).decisions().get(0).decidedBy()).isEqualTo(RESOLVER_ID);
        assertThat(dto.steps().get(0).decisions().get(0).decidedByName())
                .isEqualTo("Peter Manager");
    }

    @Test
    void toDto_decidedByNameNull_whenDecidingUserNoLongerExists() {
        ApprovalRequest request = new ApprovalRequest(
                COMPANY_ID, BRANCH_ID, "PURCHASE_ORDER", "po-uid-4",
                new BigDecimal("1000000"), "TZS", null, null, "PO to Delta",
                SUBMITTER_ID, SUBMITTER_ID);
        ReflectionTestUtils.setField(request, "id", 503L);
        ReflectionTestUtils.setField(request, "uid", "APRUID000000000000000004");

        ApprovalRequestStep step = new ApprovalRequestStep(
                503L, COMPANY_ID, BRANCH_ID, (short) 1, "PURCHASING_MANAGER", SUBMITTER_ID);
        ReflectionTestUtils.setField(step, "id", 903L);
        ReflectionTestUtils.setField(step, "uid", "STEPUID0000000000000004");

        Long ghostUserId = 999L;
        ApprovalDecision decision = new ApprovalDecision(503L, 903L, COMPANY_ID, BRANCH_ID,
                DecisionAction.REJECT, ghostUserId, "No longer valid", ghostUserId);
        ReflectionTestUtils.setField(decision, "id", 951L);
        ReflectionTestUtils.setField(decision, "uid", "DECUID0000000000000002");

        when(stepRepo.findByApprovalRequestIdOrderBySequence(503L)).thenReturn(List.of(step));
        when(decisionRepo.findByApprovalRequestStepIdOrderByDecidedAt(903L))
                .thenReturn(List.of(decision));
        when(branchRepo.findById(BRANCH_ID)).thenReturn(Optional.empty());
        when(userRepo.findById(SUBMITTER_ID)).thenReturn(Optional.empty());
        when(userRepo.findById(ghostUserId)).thenReturn(Optional.empty());
        when(roleRepo.findVisibleByCode(eq("PURCHASING_MANAGER"), any())).thenReturn(List.of());

        ApprovalRequestDto dto = engine.toDto(request);

        assertThat(dto.steps().get(0).decisions().get(0).decidedBy()).isEqualTo(ghostUserId);
        assertThat(dto.steps().get(0).decisions().get(0).decidedByName()).isNull();
    }

    @Test
    void toDto_missingUserBranchAndRole_yieldsNullNamesNeverThrows() {
        ApprovalRequest request = new ApprovalRequest(
                COMPANY_ID, BRANCH_ID, "PURCHASE_ORDER", "po-uid-2",
                new BigDecimal("2000000"), "TZS", null, null, "PO to Beta",
                SUBMITTER_ID, SUBMITTER_ID);
        ReflectionTestUtils.setField(request, "id", 501L);
        ReflectionTestUtils.setField(request, "uid", "APRUID000000000000000002");
        // resolvedBy left null — request is still PENDING.

        ApprovalRequestStep step = new ApprovalRequestStep(
                501L, COMPANY_ID, BRANCH_ID, (short) 1, "GHOST_ROLE", SUBMITTER_ID);
        ReflectionTestUtils.setField(step, "id", 901L);
        ReflectionTestUtils.setField(step, "uid", "STEPUID0000000000000002");

        when(stepRepo.findByApprovalRequestIdOrderBySequence(501L)).thenReturn(List.of(step));
        when(decisionRepo.findByApprovalRequestStepIdOrderByDecidedAt(901L)).thenReturn(List.of());
        when(branchRepo.findById(BRANCH_ID)).thenReturn(Optional.empty());
        when(userRepo.findById(SUBMITTER_ID)).thenReturn(Optional.empty());
        when(roleRepo.findVisibleByCode(eq("GHOST_ROLE"), any())).thenReturn(List.of());

        ApprovalRequestDto dto = engine.toDto(request);

        assertThat(dto.submittedByName()).isNull();
        assertThat(dto.resolvedBy()).isNull();
        assertThat(dto.resolvedByName()).isNull();
        assertThat(dto.branchName()).isNull();
        assertThat(dto.branchCode()).isNull();
        assertThat(dto.steps().get(0).approverRoleCode()).isEqualTo("GHOST_ROLE");
        assertThat(dto.steps().get(0).approverRoleName()).isNull();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Branch branchOf(String code, String name) {
        Organisation org = new Organisation("Test Org");
        Company company = new Company(org, "CO", "Test Company");
        return new Branch(company, code, name);
    }

    private static AppUser userOf(Long id, String username, String displayName) {
        AppUser user = new AppUser(username, "hash", displayName);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
