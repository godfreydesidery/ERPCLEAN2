package com.erp.api;

import com.erp.modules.hr.domain.dto.CreateNextOfKinRequest;
import com.erp.modules.hr.domain.dto.EmployeeNextOfKinDto;
import com.erp.modules.hr.domain.dto.UpdateNextOfKinRequest;
import com.erp.modules.hr.service.EmployeeNextOfKinService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Employee next-of-kin sub-resource (ADR-0040 D-11).
 * URL pattern: /api/v1/hr/employees/uid/{employeeUid}/next-of-kin
 *
 * <p>Reuses the HR.EMPLOYEE.VIEW / HR.EMPLOYEE.MANAGE permissions; the service enforces
 * employee-scope (target lives in the caller's active company) and the at-most-one-primary rule.
 */
@RestController
@RequestMapping("/api/v1/hr/employees/uid/{employeeUid}/next-of-kin")
public class HrEmployeeNextOfKinController {

    private final EmployeeNextOfKinService service;

    public HrEmployeeNextOfKinController(EmployeeNextOfKinService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.EMPLOYEE.VIEW')")
    public List<EmployeeNextOfKinDto> list(@PathVariable String employeeUid) {
        return service.list(employeeUid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.EMPLOYEE.MANAGE')")
    public EmployeeNextOfKinDto add(@PathVariable String employeeUid,
                                    @Valid @RequestBody CreateNextOfKinRequest req) {
        return service.add(employeeUid, req);
    }

    @PutMapping("/uid/{nokUid}")
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.EMPLOYEE.MANAGE')")
    public EmployeeNextOfKinDto update(@PathVariable String employeeUid,
                                       @PathVariable String nokUid,
                                       @Valid @RequestBody UpdateNextOfKinRequest req) {
        return service.update(employeeUid, nokUid, req);
    }

    @DeleteMapping("/uid/{nokUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#employeeUid,'employee','HR.EMPLOYEE.MANAGE')")
    public void deactivate(@PathVariable String employeeUid, @PathVariable String nokUid) {
        service.deactivate(employeeUid, nokUid);
    }
}
