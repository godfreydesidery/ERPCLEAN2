package com.erp.api;

import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.DisburseRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.service.PayrollRunService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/v1/hr/payroll-runs")
public class HrPayrollController {

    private final PayrollRunService service;
    private final ScopeGuard        scopeGuard;

    public HrPayrollController(PayrollRunService service, ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.PAYROLL.VIEW')")
    public ApiResponse<List<PayrollRunDto>> list(@RequestParam Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<PayrollRunDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.PAYROLL.RUN')")
    public PayrollRunDto create(@Valid @RequestBody CreatePayrollRunRequest req) {
        return service.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW')")
    public PayrollRunDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW')")
    public List<PayrollLineDto> lines(@PathVariable String uid) {
        return service.listLines(uid);
    }

    @PostMapping("/uid/{uid}/calculate")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.RUN')")
    public PayrollRunDto calculate(@PathVariable String uid) {
        return service.calculate(uid);
    }

    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.APPROVE')")
    public PayrollRunDto approve(@PathVariable String uid) {
        return service.approve(uid);
    }

    @PostMapping("/uid/{uid}/post")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.POST')")
    public PayrollRunDto post(@PathVariable String uid) {
        return service.post(uid);
    }

    @PostMapping("/uid/{uid}/disburse")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.DISBURSE')")
    public PayrollRunDto disburse(@PathVariable String uid,
                                   @Valid @RequestBody DisburseRequest req) {
        return service.disburse(uid, req);
    }

    @PostMapping("/uid/{uid}/reverse")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.REVERSE')")
    public PayrollRunDto reverse(@PathVariable String uid) {
        return service.reverse(uid);
    }

    /**
     * Export EFT batch as CSV for the payroll run (ADR-0040 D-11).
     * Gated by HR.PAYROLL.DISBURSE — same permission as disburse.
     */
    @GetMapping("/uid/{uid}/eft-export")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.DISBURSE')")
    public ResponseEntity<String> eftExport(@PathVariable String uid) {
        String csv = service.exportEftBatch(uid);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition",
                        "attachment; filename=\"eft-batch-" + uid + ".csv\"")
                .body(csv);
    }
}
