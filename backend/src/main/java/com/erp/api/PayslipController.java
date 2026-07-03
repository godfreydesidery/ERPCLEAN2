package com.erp.api;

import com.erp.modules.hr.domain.dto.PayslipDto;
import com.erp.modules.hr.service.PayslipService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only payslip access (ADR-0032 D-6). Payslips are generated as a side effect of posting a
 * payroll run — see {@code HrPayrollController} for the per-run list endpoint. */
@RestController
@RequestMapping("/api/v1/hr/payslips")
public class PayslipController {

    private final PayslipService service;

    public PayslipController(PayslipService service) {
        this.service = service;
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'payslip','HR.PAYROLL.VIEW')")
    public PayslipDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }
}
