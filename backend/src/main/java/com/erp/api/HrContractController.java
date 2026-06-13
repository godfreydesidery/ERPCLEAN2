package com.erp.api;

import com.erp.modules.hr.domain.dto.ContractDto;
import com.erp.modules.hr.domain.dto.CreateContractRequest;
import com.erp.modules.hr.service.ContractServiceImpl;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/contracts")
public class HrContractController {

    private final ContractServiceImpl service;
    private final ScopeGuard          scopeGuard;

    public HrContractController(ContractServiceImpl service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.EMPLOYEE.VIEW')")
    public List<ContractDto> listByEmployee(@RequestParam Long companyId,
                                             @RequestParam Long employeeId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return service.listByEmployee(companyId, employeeId);
    }

    @PostMapping("/employee/{employeeUid}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.EMPLOYEE.MANAGE')")
    public ContractDto create(@PathVariable String employeeUid,
                               @Valid @RequestBody CreateContractRequest req) {
        return service.createForEmployee(employeeUid, req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'employmentcontract','HR.EMPLOYEE.VIEW')")
    public ContractDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @DeleteMapping("/uid/{uid}/terminate")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'employmentcontract','HR.EMPLOYEE.MANAGE')")
    public void terminate(@PathVariable String uid) {
        service.terminate(uid);
    }
}
