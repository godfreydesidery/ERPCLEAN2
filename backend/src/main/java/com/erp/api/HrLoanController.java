package com.erp.api;

import com.erp.modules.hr.domain.dto.CreateLoanRequest;
import com.erp.modules.hr.domain.dto.EmployeeLoanDto;
import com.erp.modules.hr.service.EmployeeLoanService;
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
@RequestMapping("/api/v1/hr/loans")
public class HrLoanController {

    private final EmployeeLoanService service;
    private final ScopeGuard          scopeGuard;

    public HrLoanController(EmployeeLoanService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.LOAN.MANAGE')")
    public ApiResponse<List<EmployeeLoanDto>> list(@RequestParam Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<EmployeeLoanDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping("/employee/{employeeUid}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.LOAN.MANAGE')")
    public EmployeeLoanDto create(@PathVariable String employeeUid,
                                   @Valid @RequestBody CreateLoanRequest req) {
        return service.create(employeeUid, req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'employeeloan','HR.LOAN.MANAGE')")
    public EmployeeLoanDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid,'employeeloan','HR.LOAN.MANAGE')")
    public EmployeeLoanDto approve(@PathVariable String uid) {
        return service.approve(uid);
    }
}
