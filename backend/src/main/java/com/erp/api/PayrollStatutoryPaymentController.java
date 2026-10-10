package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.hr.domain.dto.RecordStatutoryPaymentRequest;
import com.erp.modules.hr.domain.dto.StatutoryLiabilityBalanceDto;
import com.erp.modules.hr.service.PayrollStatutoryPaymentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * ACC-07: paying payroll statutory liabilities (PAYE, NSSF, WCF, SDL, HESLB) to the authorities, from
 * the payroll statutory report. Company from the request context, never a parameter.
 *
 * <p>Gates: the outstanding balances are statutory totals the statutory report already shows at
 * {@code HR.PAYROLL.VIEW}; paying is {@code HR.PAYROLL.DISBURSE} ("Disburse net wages and statutory
 * payables via Cash &amp; Bank").
 */
@RestController
@RequestMapping("/api/v1/hr/payroll/statutory-payments")
public class PayrollStatutoryPaymentController {

    private final PayrollStatutoryPaymentService service;

    public PayrollStatutoryPaymentController(PayrollStatutoryPaymentService service) {
        this.service = service;
    }

    @GetMapping("/outstanding")
    @PreAuthorize("@perm.has('HR.PAYROLL.VIEW')")
    public List<StatutoryLiabilityBalanceDto> outstanding() {
        return service.outstanding();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.PAYROLL.DISBURSE')")
    public CashTransactionDto pay(@Valid @RequestBody RecordStatutoryPaymentRequest req) {
        return service.pay(req);
    }
}
