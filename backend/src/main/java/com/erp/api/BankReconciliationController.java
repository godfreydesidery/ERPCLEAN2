package com.erp.api;

import com.erp.modules.cashbank.domain.dto.BankReconciliationDto;
import com.erp.modules.cashbank.domain.dto.MarkClearedRequest;
import com.erp.modules.cashbank.domain.dto.OpenReconciliationRequest;
import com.erp.modules.cashbank.service.BankReconciliationService;
import jakarta.validation.Valid;
import java.util.List;
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

/**
 * Bank reconciliation lifecycle: DRAFT → COMPLETED (ADR-0016 D-6, FR-CASH-13..15, BR-CASH-06/07/11).
 * Completion blocked unless clearedBookBalance == statementClosingBalance (BigDecimal.compareTo).
 * Permission: CASH.RECONCILE for writes; CASH.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/cash/reconciliations")
public class BankReconciliationController {

    private final BankReconciliationService service;

    public BankReconciliationController(BankReconciliationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CASH.RECONCILE')")
    public BankReconciliationDto open(@Valid @RequestBody OpenReconciliationRequest req) {
        return service.open(req);
    }

    @PostMapping("/uid/{uid}/mark-cleared")
    @PreAuthorize("@perm.scoped(#uid,'bankreconciliation','CASH.RECONCILE')")
    public BankReconciliationDto markCleared(@PathVariable String uid,
                                              @Valid @RequestBody MarkClearedRequest req) {
        return service.markCleared(uid, req);
    }

    @PostMapping("/uid/{uid}/complete")
    @PreAuthorize("@perm.scoped(#uid,'bankreconciliation','CASH.RECONCILE')")
    public BankReconciliationDto complete(@PathVariable String uid) {
        return service.complete(uid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'bankreconciliation','CASH.VIEW')")
    public BankReconciliationDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<BankReconciliationDto> listByAccount(@RequestParam Long companyId,
                                                      @RequestParam Long accountId) {
        return service.listByAccount(companyId, accountId);
    }
}
