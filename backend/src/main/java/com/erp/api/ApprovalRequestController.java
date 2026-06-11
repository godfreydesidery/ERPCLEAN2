package com.erp.api;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.DecideRequest;
import com.erp.modules.approvals.service.ApprovalDecisionService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approval request REST controller (ADR-0022, FR-APR-07/08/10/12/13/15).
 * Responses auto-wrapped by ApiResponseAdvice. Perms seeded in V20__approvals_engine.sql.
 *
 * <p>Note: {@code submitForApproval} / {@code getApprovalState} are NOT REST endpoints — they
 * are the {@code ApprovalEngine} service interface that consuming modules call in-process (D-7).
 */
@RestController
@RequestMapping("/api/v1/approvals/requests")
public class ApprovalRequestController {

    private final ApprovalDecisionService decisionService;

    public ApprovalRequestController(ApprovalDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    /** Approvals inbox — PENDING requests whose current open step the caller can decide (FR-APR-12). */
    @GetMapping("/inbox")
    @PreAuthorize("@perm.has('APPROVALS.DECIDE')")
    public ApiResponse<List<ApprovalRequestDto>> inbox(Pageable pageable) {
        Page<ApprovalRequestDto> page = decisionService.inbox(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')")
    public ApprovalRequestDto getByUid(@PathVariable String uid) {
        return decisionService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('APPROVALS.REQUEST.VIEW')")
    public ApiResponse<List<ApprovalRequestDto>> list(@RequestParam Long companyId,
                                                       @RequestParam(required = false) String status,
                                                       Pageable pageable) {
        Page<ApprovalRequestDto> page = status != null
                ? decisionService.listByCompanyAndStatus(companyId, status, pageable)
                : decisionService.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** APPROVE the current open step (FR-APR-07). Fine routing enforced in service (StepApproverResolver). */
    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')")
    public ApprovalRequestDto approve(@PathVariable String uid,
                                      @Valid @RequestBody DecideRequest request) {
        return decisionService.decide(uid, request);
    }

    /** REJECT the current open step — kills the whole chain (FR-APR-08, OQ-APR-04 default). */
    @PostMapping("/uid/{uid}/reject")
    @PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.DECIDE')")
    public ApprovalRequestDto reject(@PathVariable String uid,
                                     @Valid @RequestBody DecideRequest request) {
        return decisionService.decide(uid, request);
    }

    /** Submitter recalls own PENDING request (FR-APR-10). Service enforces submitter-only. */
    @PostMapping("/uid/{uid}/recall")
    @PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.REQUEST.VIEW')")
    public ApprovalRequestDto recall(@PathVariable String uid) {
        return decisionService.recall(uid);
    }

    /** Admin cancel — any non-terminal request (FR-APR-15, APPROVALS.ADMIN). */
    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.scoped(#uid,'approvalrequest','APPROVALS.ADMIN')")
    public ApprovalRequestDto cancel(@PathVariable String uid) {
        return decisionService.cancel(uid);
    }
}
