package com.erp.modules.approvals;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.approvals.domain.dto.ApprovalPolicyDto;
import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.CreateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.dto.DecideRequest;
import com.erp.modules.approvals.domain.dto.PolicyStepInputDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.dto.UpdateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.domain.enums.DecisionAction;
import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.modules.approvals.service.ApprovalDecisionService;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.approvals.service.ApprovalPolicyService;
import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the core Approvals Workflow Engine flows (ADR-0022).
 *
 * <p>Covers:
 * <ul>
 *   <li>Happy path: two-step policy match → PENDING → APPROVE step 1 → APPROVE step 2 → APPROVED</li>
 *   <li>Auto-approve: no policy match → terminal APPROVED with no steps</li>
 *   <li>Reject kills chain: REJECT step 1 → whole request REJECTED, step 2 SKIPPED</li>
 *   <li>Idempotent submit: re-submit same (type, uid) returns existing request</li>
 *   <li>SoD enforcement: submitter cannot approve own request</li>
 *   <li>Recall: submitter recalls PENDING request → RECALLED</li>
 * </ul>
 */
class ApprovalsEngineIT extends PostgresIntegrationTest {

    @Autowired private ApprovalPolicyService    policyService;
    @Autowired private ApprovalEngine           engine;
    @Autowired private ApprovalDecisionService  decisionService;

    @Autowired private IamTestData              testData;
    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private RoleRepository           roles;
    @Autowired private UserBranchRepository     userBranches;
    @Autowired private UserRoleService          userRoleService;
    @Autowired private PasswordEncoder          passwordEncoder;

    private Organisation org;
    private Company      company;
    private Branch       branch;

    private AppUser buyerUser;      // submitter
    private AppUser pmUser;         // holds PURCHASING_MANAGER role (step 1 approver)
    private AppUser fmUser;         // holds FINANCE_MANAGER role (step 2 approver)

    private Role pmRole;
    private Role fmRole;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        org     = organisations.save(new Organisation("Approvals Test Org"));
        company = companies.save(new Company(org, "APPR", "Approvals Test Co"));
        branch  = branches.save(new Branch(company, "APPR-HQ", "Approvals HQ"));

        buyerUser = new AppUser("buyer", passwordEncoder.encode("pass"), "Buyer User");
        buyerUser = users.save(inOrganisation(buyerUser, org.getId()));
        UserBranch buyerAssign = new UserBranch(buyerUser.getId(), branch, buyerUser.getId());
        buyerAssign.markDefault();
        userBranches.save(buyerAssign);

        pmUser = new AppUser("pm_user", passwordEncoder.encode("pass"), "PM User");
        pmUser = users.save(inOrganisation(pmUser, org.getId()));
        UserBranch pmAssign = new UserBranch(pmUser.getId(), branch, pmUser.getId());
        pmAssign.markDefault();
        userBranches.save(pmAssign);

        fmUser = new AppUser("fm_user", passwordEncoder.encode("pass"), "FM User");
        fmUser = users.save(inOrganisation(fmUser, org.getId()));
        UserBranch fmAssign = new UserBranch(fmUser.getId(), branch, fmUser.getId());
        fmAssign.markDefault();
        userBranches.save(fmAssign);

        // Create roles (is_system=false — IamTestData.clearAll can delete them)
        pmRole = roles.save(new Role("PURCHASING_MANAGER", "PM Role"));
        fmRole = roles.save(new Role("FINANCE_MANAGER",   "FM Role"));

        // Grant roles (as the buyer user acting as admin)
        asPrincipal(buyerUser);
        testData.seedMembership(pmUser.getUid(), company.getUid());
        userRoleService.grant(new GrantRoleRequest(
                pmUser.getUid(), pmRole.getUid(), company.getUid(), branch.getUid()));
        testData.seedMembership(fmUser.getUid(), company.getUid());
        userRoleService.grant(new GrantRoleRequest(
                fmUser.getUid(), fmRole.getUid(), company.getUid(), branch.getUid()));
        RequestContext.clear();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ------------------------------------------------------------------
    // 1. Happy path: two-step approval → fully APPROVED
    // ------------------------------------------------------------------
    @Test
    void happyPath_twoStepApproval() {
        asPrincipal(buyerUser);

        // Create a two-step policy
        ApprovalPolicyDto policy = policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "High-value PO",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), new BigDecimal("100000000"),
                null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"),
                        new PolicyStepInputDto(2, "FINANCE_MANAGER"))
        ));

        // Submit a PO for approval
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-001", new BigDecimal("12000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "PO to Acme"));

        assertThat(request.status()).isEqualTo(ApprovalRequestStatus.PENDING);
        assertThat(request.autoApproved()).isFalse();
        assertThat(request.steps()).hasSize(2);
        assertThat(request.requestNumber()).startsWith("APPROVAL-");

        String requestUid = request.uid();

        // Step 1: PM approves
        asPrincipal(pmUser);
        ApprovalRequestDto afterStep1 = decisionService.decide(requestUid,
                new DecideRequest(DecisionAction.APPROVE, "Looks good"));
        assertThat(afterStep1.status()).isEqualTo(ApprovalRequestStatus.PENDING);

        // Step 2: FM approves → request resolves APPROVED
        asPrincipal(fmUser);
        ApprovalRequestDto resolved = decisionService.decide(requestUid,
                new DecideRequest(DecisionAction.APPROVE, "Approved by FM"));
        assertThat(resolved.status()).isEqualTo(ApprovalRequestStatus.APPROVED);
        assertThat(resolved.resolvedAt()).isNotNull();
        assertThat(resolved.steps()).allMatch(s -> s.status().name().equals("APPROVED"));

        // getApprovalState reflects APPROVED
        asPrincipal(buyerUser);
        Optional<ApprovalRequestDto> state = engine.getApprovalState(
                "PURCHASE_ORDER", "po-uid-001", company.getId());
        assertThat(state).isPresent();
        assertThat(state.get().status()).isEqualTo(ApprovalRequestStatus.APPROVED);
    }

    // ------------------------------------------------------------------
    // 2. Auto-approve: no policy match → terminal APPROVED, no steps
    // ------------------------------------------------------------------
    @Test
    void autoApprove_whenNoPolicyMatch() {
        asPrincipal(buyerUser);
        // No policy configured → small PO auto-approves
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-auto", new BigDecimal("200000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "Small PO"));

        assertThat(request.status()).isEqualTo(ApprovalRequestStatus.APPROVED);
        assertThat(request.autoApproved()).isTrue();
        assertThat(request.steps()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 3. Reject kills the chain
    // ------------------------------------------------------------------
    @Test
    void reject_killsEntireChain() {
        asPrincipal(buyerUser);
        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "High-value PO",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), new BigDecimal("100000000"), null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"),
                        new PolicyStepInputDto(2, "FINANCE_MANAGER"))
        ));

        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-rej", new BigDecimal("12000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "PO to Acme"));
        String requestUid = request.uid();

        // PM rejects step 1 → whole request REJECTED, step 2 SKIPPED
        asPrincipal(pmUser);
        ApprovalRequestDto rejected = decisionService.decide(requestUid,
                new DecideRequest(DecisionAction.REJECT, "Wrong supplier"));

        assertThat(rejected.status()).isEqualTo(ApprovalRequestStatus.REJECTED);
        assertThat(rejected.steps().get(0).status().name()).isEqualTo("REJECTED");
        assertThat(rejected.steps().get(1).status().name()).isEqualTo("SKIPPED");
    }

    // ------------------------------------------------------------------
    // 4. Idempotent submit — re-submit same (type, uid) returns existing PENDING request
    //    (idempotency guard only returns the same request when it is still PENDING;
    //     re-submitting a terminal request is a ConflictException per OQ-APR-06 / Finding 5 fix).
    // ------------------------------------------------------------------
    @Test
    void idempotentSubmit_returnsSameRequest() {
        asPrincipal(buyerUser);
        // Create a policy so the submit goes PENDING (not auto-approved) — amount 5M > threshold 1M
        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Idem Policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), null, null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        ));

        ApprovalRequestDto first = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-idem", new BigDecimal("5000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), null));

        assertThat(first.status()).isEqualTo(ApprovalRequestStatus.PENDING);

        // Re-submit the same document uid with same amount — must return the EXISTING PENDING request
        ApprovalRequestDto second = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-idem", new BigDecimal("5000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), null));

        assertThat(second.uid()).isEqualTo(first.uid());
        assertThat(second.requestNumber()).isEqualTo(first.requestNumber());
        assertThat(second.status()).isEqualTo(ApprovalRequestStatus.PENDING);
    }

    // ------------------------------------------------------------------
    // 5. SoD: submitter cannot approve own request
    // ------------------------------------------------------------------
    @Test
    void sod_submitterCannotApproveOwnRequest() {
        // Give the buyer the PM role too
        asPrincipal(buyerUser);
        testData.seedMembership(buyerUser.getUid(), company.getUid());
        userRoleService.grant(new GrantRoleRequest(
                buyerUser.getUid(), pmRole.getUid(), company.getUid(), branch.getUid()));

        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), null, null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        ));
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-sod", new BigDecimal("2000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "SoD test"));

        // Buyer tries to approve own request — must be rejected with ConflictException
        assertThatThrownBy(() ->
                decisionService.decide(request.uid(), new DecideRequest(DecisionAction.APPROVE, null))
        ).isInstanceOf(ConflictException.class)
         .hasMessageContaining("not authorised");
    }

    // ------------------------------------------------------------------
    // 6. Recall: submitter recalls PENDING request
    // ------------------------------------------------------------------
    @Test
    void recall_pendingRequest() {
        asPrincipal(buyerUser);
        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), null, null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        ));
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-recall", new BigDecimal("5000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "Recall me"));

        ApprovalRequestDto recalled = decisionService.recall(request.uid());
        assertThat(recalled.status()).isEqualTo(ApprovalRequestStatus.RECALLED);
        assertThat(recalled.steps().get(0).status().name()).isEqualTo("SKIPPED");
    }

    // ------------------------------------------------------------------
    // 7. Finding 1 fix: reject atomically SKIPs ALL later steps (not just the first)
    //    Verifies the batch UPDATE path rather than the old entity-loop.
    // ------------------------------------------------------------------
    @Test
    void reject_skipsAllLaterSteps_atomically() {
        asPrincipal(buyerUser);
        // Three-step policy: PM → FM → buyer (we reuse buyerUser as step-3 role holder for simplicity)
        Role step3Role = roles.save(new Role("STEP3_ROLE", "Step3 Role"));
        asPrincipal(buyerUser);
        testData.seedMembership(fmUser.getUid(), company.getUid());
        userRoleService.grant(new GrantRoleRequest(
                fmUser.getUid(), step3Role.getUid(), company.getUid(), branch.getUid()));

        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Three-step policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), new BigDecimal("100000000"), null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"),
                        new PolicyStepInputDto(2, "FINANCE_MANAGER"),
                        new PolicyStepInputDto(3, "STEP3_ROLE"))
        ));
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-3step", new BigDecimal("12000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "3-step PO"));

        assertThat(request.steps()).hasSize(3);

        // PM rejects step 1 → request REJECTED, steps 2 and 3 both SKIPPED
        asPrincipal(pmUser);
        ApprovalRequestDto rejected = decisionService.decide(request.uid(),
                new DecideRequest(DecisionAction.REJECT, "Not approved"));

        assertThat(rejected.status()).isEqualTo(ApprovalRequestStatus.REJECTED);
        assertThat(rejected.steps().get(0).status().name()).isEqualTo("REJECTED");
        // Both remaining steps must be SKIPPED — proves the atomic batch UPDATE worked
        assertThat(rejected.steps().get(1).status().name()).isEqualTo("SKIPPED");
        assertThat(rejected.steps().get(2).status().name()).isEqualTo("SKIPPED");
    }

    // ------------------------------------------------------------------
    // 8. Finding 2 fix: inbox does NOT show requests from branches the caller has no access to
    // ------------------------------------------------------------------
    @Test
    void inbox_excludesRequestsFromUnassignedBranches() {
        // Create a second branch; pmUser is NOT assigned to it
        Branch branch2 = branches.save(new Branch(company, "APPR-B2", "Approvals Branch 2"));

        asPrincipal(buyerUser);
        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), null, null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        ));

        // Submit the PO from branch2 — pmUser has no user_branch assignment for branch2
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-branch2", new BigDecimal("5000000"), "TZS",
                company.getId(), branch2.getUid(), buyerUser.getId(), "Branch-2 PO"));

        assertThat(request.status()).isEqualTo(ApprovalRequestStatus.PENDING);

        // pmUser has the PURCHASING_MANAGER role but no access to branch2 — must not appear in inbox
        asPrincipal(pmUser);
        Page<ApprovalRequestDto> inboxPage = decisionService.inbox(PageRequest.of(0, 20));
        assertThat(inboxPage.getContent())
                .as("PM user has no branch-2 access; request from branch-2 must not appear in inbox")
                .noneMatch(r -> r.uid().equals(request.uid()));
    }

    // ------------------------------------------------------------------
    // 9. Finding 5 fix: re-submitting a terminal (REJECTED) request with same UID → ConflictException
    // ------------------------------------------------------------------
    @Test
    void resubmit_afterRejection_withSameUid_throwsConflict() {
        asPrincipal(buyerUser);
        policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Policy",
                PolicyBranchScope.COMPANY_WIDE, null,
                new BigDecimal("1000000"), null, null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        ));
        ApprovalRequestDto request = engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-resubmit", new BigDecimal("5000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "PO to reject"));

        // PM rejects it
        asPrincipal(pmUser);
        ApprovalRequestDto rejected = decisionService.decide(request.uid(),
                new DecideRequest(DecisionAction.REJECT, "Rejected"));
        assertThat(rejected.status()).isEqualTo(ApprovalRequestStatus.REJECTED);

        // Consumer attempts re-submit with same document UID — must throw ConflictException (OQ-APR-06)
        asPrincipal(buyerUser);
        assertThatThrownBy(() -> engine.submitForApproval(new SubmitForApprovalRequest(
                "PURCHASE_ORDER", "po-uid-resubmit", new BigDecimal("5000000"), "TZS",
                company.getId(), branch.getUid(), buyerUser.getId(), "Re-submitted PO")))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class)
                .hasMessageContaining("already been resolved")
                .hasMessageContaining("REJECTED");
    }

    // ------------------------------------------------------------------
    // 10. Security: confused-deputy — foreign branch uid rejected on create and update
    //     A Company-A admin POSTing/PUTting an approval policy with a branchUid that belongs to
    //     Company-B must receive 404 (NotFoundException) — not 201/200 — and no cross-tenant FK
    //     must be persisted.
    // ------------------------------------------------------------------
    @Test
    void createPolicy_withForeignBranchUid_throwsNotFound() {
        // Arrange: a second company + branch (Company B)
        Company companyB = companies.save(new Company(org, "COMP-B", "Company B"));
        Branch  branchB  = branches.save(new Branch(companyB, "B-HQ", "Company B HQ"));

        asPrincipal(buyerUser); // scoped to company A

        // Act + Assert: supplying companyB's branch uid while scopeGuard passes for companyA
        assertThatThrownBy(() ->
            policyService.create(new CreateApprovalPolicyRequest(
                company.getId(), "PURCHASE_ORDER", "Bad Policy",
                PolicyBranchScope.BRANCH, branchB.getUid(), // <-- foreign branch
                new BigDecimal("1000000"), null, null,
                List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
            ))
        ).isInstanceOf(NotFoundException.class)
         .hasMessageContaining("Branch not found");
    }

    @Test
    void updatePolicy_withForeignBranchUid_throwsNotFound() {
        // Arrange: create a valid COMPANY_WIDE policy in company A first
        asPrincipal(buyerUser);
        ApprovalPolicyDto policy = policyService.create(new CreateApprovalPolicyRequest(
            company.getId(), "PURCHASE_ORDER", "Valid Policy",
            PolicyBranchScope.COMPANY_WIDE, null,
            new BigDecimal("1000000"), null, null,
            List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        ));

        // Arrange: a second company + branch (Company B)
        Company companyB  = companies.save(new Company(org, "COMP-B2", "Company B2"));
        Branch  branchB   = branches.save(new Branch(companyB, "B2-HQ", "Company B2 HQ"));
        String  policyUid = policy.uid();
        String  foreignUid = branchB.getUid();
        UpdateApprovalPolicyRequest badReq = new UpdateApprovalPolicyRequest(
            "PURCHASE_ORDER", "Updated Policy",
            PolicyBranchScope.BRANCH, foreignUid, // <-- foreign branch
            new BigDecimal("1000000"), null, null, true,
            List.of(new PolicyStepInputDto(1, "PURCHASING_MANAGER"))
        );

        // Act + Assert: update switches scope to BRANCH using companyB's branch uid
        assertThatThrownBy(() -> policyService.update(policyUid, badReq))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("Branch not found");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void asPrincipal(AppUser user) {
        RequestContext.set(new RequestContext.Principal(
                user.getId(), user.getUsername(), false,
                company.getId(), branch.getId(), "127.0.0.1"));
    }
}
