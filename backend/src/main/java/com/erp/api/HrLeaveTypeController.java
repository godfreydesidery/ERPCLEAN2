package com.erp.api;

import com.erp.modules.hr.domain.dto.CreateLeaveTypeRequest;
import com.erp.modules.hr.domain.dto.LeaveTypeDto;
import com.erp.modules.hr.service.LeaveTypeService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/leave-types")
public class HrLeaveTypeController {

    private final LeaveTypeService service;
    private final ScopeGuard       scopeGuard;

    public HrLeaveTypeController(LeaveTypeService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.LEAVE.VIEW')")
    public ApiResponse<List<LeaveTypeDto>> list(@RequestParam Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(service.listByCompany(companyId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.LEAVE.MANAGE')")
    public LeaveTypeDto create(@Valid @RequestBody CreateLeaveTypeRequest req) {
        return service.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'leavetype','HR.LEAVE.VIEW')")
    public LeaveTypeDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'leavetype','HR.LEAVE.MANAGE')")
    public LeaveTypeDto update(@PathVariable String uid,
                                @Valid @RequestBody CreateLeaveTypeRequest req) {
        return service.update(uid, req);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'leavetype','HR.LEAVE.MANAGE')")
    public void deactivate(@PathVariable String uid) {
        service.deactivate(uid);
    }
}
