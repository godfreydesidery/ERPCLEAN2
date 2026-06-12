package com.erp.api;

import com.erp.modules.hr.domain.dto.CreateDepartmentRequest;
import com.erp.modules.hr.domain.dto.DepartmentDto;
import com.erp.modules.hr.service.DepartmentService;
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
@RequestMapping("/api/v1/hr/departments")
public class HrDepartmentController {

    private final DepartmentService service;
    private final ScopeGuard        scopeGuard;

    public HrDepartmentController(DepartmentService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.EMPLOYEE.VIEW')")
    public ApiResponse<List<DepartmentDto>> list(@RequestParam Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(service.listByCompany(companyId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.EMPLOYEE.MANAGE')")
    public DepartmentDto create(@Valid @RequestBody CreateDepartmentRequest req) {
        return service.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'hrdepartment','HR.EMPLOYEE.VIEW')")
    public DepartmentDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'hrdepartment','HR.EMPLOYEE.MANAGE')")
    public DepartmentDto update(@PathVariable String uid,
                                 @Valid @RequestBody CreateDepartmentRequest req) {
        return service.update(uid, req);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'hrdepartment','HR.EMPLOYEE.MANAGE')")
    public void deactivate(@PathVariable String uid) {
        service.deactivate(uid);
    }
}
