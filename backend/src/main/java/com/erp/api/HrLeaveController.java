package com.erp.api;

import com.erp.modules.hr.domain.dto.DecideLeaveRequest;
import com.erp.modules.hr.domain.dto.LeaveRequestDto;
import com.erp.modules.hr.domain.dto.SubmitLeaveRequest;
import com.erp.modules.hr.service.LeaveService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/leave-requests")
public class HrLeaveController {

    private final LeaveService service;
    private final ScopeGuard   scopeGuard;

    public HrLeaveController(LeaveService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.LEAVE.VIEW')")
    public ApiResponse<List<LeaveRequestDto>> list(@RequestParam Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<LeaveRequestDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping("/employee/{employeeUid}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.LEAVE.MANAGE')")
    public LeaveRequestDto submit(@PathVariable String employeeUid,
                                   @Valid @RequestBody SubmitLeaveRequest req) {
        return service.submit(employeeUid, req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'leaverequest','HR.LEAVE.VIEW')")
    public LeaveRequestDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PostMapping("/uid/{uid}/decide")
    @PreAuthorize("@perm.scoped(#uid,'leaverequest','HR.LEAVE.APPROVE')")
    public LeaveRequestDto decide(@PathVariable String uid,
                                   @Valid @RequestBody DecideLeaveRequest req) {
        return service.decide(uid, req);
    }
}
