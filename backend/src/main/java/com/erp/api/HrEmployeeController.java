package com.erp.api;

import com.erp.modules.hr.domain.dto.CreateEmployeeRequest;
import com.erp.modules.hr.domain.dto.EmployeeDto;
import com.erp.modules.hr.service.EmployeeService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
@RequestMapping("/api/v1/hr/employees")
public class HrEmployeeController {

    private final EmployeeService service;
    private final ScopeGuard      scopeGuard;

    public HrEmployeeController(EmployeeService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.EMPLOYEE.VIEW')")
    public ApiResponse<java.util.List<EmployeeDto>> list(@RequestParam Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<EmployeeDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.EMPLOYEE.MANAGE')")
    public EmployeeDto create(@Valid @RequestBody CreateEmployeeRequest req) {
        return service.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'employee','HR.EMPLOYEE.VIEW')")
    public EmployeeDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'employee','HR.EMPLOYEE.MANAGE')")
    public EmployeeDto update(@PathVariable String uid,
                               @Valid @RequestBody CreateEmployeeRequest req) {
        return service.update(uid, req);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'employee','HR.EMPLOYEE.MANAGE')")
    public void archive(@PathVariable String uid) {
        service.archive(uid);
    }
}
