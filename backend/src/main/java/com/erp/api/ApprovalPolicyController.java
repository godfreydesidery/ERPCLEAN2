package com.erp.api;

import com.erp.modules.approvals.domain.dto.ApprovalPolicyDto;
import com.erp.modules.approvals.domain.dto.CreateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.dto.UpdateApprovalPolicyRequest;
import com.erp.modules.approvals.service.ApprovalPolicyService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Approval policy master CRUD (ADR-0022, FR-APR-01/02).
 * Responses auto-wrapped by ApiResponseAdvice. Perms seeded in V20__approvals_engine.sql.
 */
@RestController
@RequestMapping("/api/v1/approvals/policies")
public class ApprovalPolicyController {

    private final ApprovalPolicyService policyService;

    public ApprovalPolicyController(ApprovalPolicyService policyService) {
        this.policyService = policyService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('APPROVALS.POLICY.MANAGE')")
    public ApprovalPolicyDto create(@Valid @RequestBody CreateApprovalPolicyRequest request) {
        return policyService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')")
    public ApprovalPolicyDto update(@PathVariable String uid,
                                    @Valid @RequestBody UpdateApprovalPolicyRequest request) {
        return policyService.update(uid, request);
    }

    @PostMapping("/uid/{uid}/deactivate")
    @PreAuthorize("@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.MANAGE')")
    public ApprovalPolicyDto deactivate(@PathVariable String uid) {
        return policyService.deactivate(uid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'approvalpolicy','APPROVALS.POLICY.VIEW')")
    public ApprovalPolicyDto getByUid(@PathVariable String uid) {
        return policyService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('APPROVALS.POLICY.VIEW')")
    public ApiResponse<List<ApprovalPolicyDto>> list(@RequestParam Long companyId,
                                                      @RequestParam(required = false) String documentType,
                                                      Pageable pageable) {
        Page<ApprovalPolicyDto> page = documentType != null
                ? policyService.listByCompanyAndDocumentType(companyId, documentType, pageable)
                : policyService.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
