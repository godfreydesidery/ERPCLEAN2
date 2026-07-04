package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashCountDto;
import com.erp.modules.cashbank.domain.dto.OpenCashCountRequest;
import com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest;
import com.erp.modules.cashbank.service.CashCountService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * End-of-day cash count against a CASH till (ADR-0050 D-7 PR-A).
 * Permission: CASH.COUNT.MANAGE for open/denominations/reconcile; CASH.COUNT.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/cash/counts")
public class CashCountController {

    private final CashCountService service;

    public CashCountController(CashCountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CASH.COUNT.MANAGE')")
    public CashCountDto open(@Valid @RequestBody OpenCashCountRequest req) {
        return service.open(req);
    }

    @PutMapping("/uid/{uid}/denominations")
    @PreAuthorize("@perm.scoped(#uid,'cashcount','CASH.COUNT.MANAGE')")
    public CashCountDto recordDenominations(@PathVariable String uid,
                                             @Valid @RequestBody RecordDenominationsRequest req) {
        return service.recordDenominations(uid, req);
    }

    @PostMapping("/uid/{uid}/reconcile")
    @PreAuthorize("@perm.scoped(#uid,'cashcount','CASH.COUNT.MANAGE')")
    public CashCountDto reconcile(@PathVariable String uid) {
        return service.reconcile(uid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'cashcount','CASH.COUNT.VIEW')")
    public CashCountDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CASH.COUNT.VIEW')")
    public List<CashCountDto> listByAccount(@RequestParam Long companyId,
                                             @RequestParam Long accountId) {
        return service.listByAccount(companyId, accountId);
    }
}
