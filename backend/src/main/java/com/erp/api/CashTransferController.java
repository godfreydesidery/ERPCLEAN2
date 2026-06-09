package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashTransferDto;
import com.erp.modules.cashbank.domain.dto.RecordTransferRequest;
import com.erp.modules.cashbank.service.CashTransferService;
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
 * Cash transfers between accounts (ADR-0016 D-3, FR-CASH-08, BR-CASH-03/04).
 * Posts two cash_transactions + one balanced GL entry (CBT-####) atomically.
 * Permission: CASH.TRANSFER for writes; CASH.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/cash/transfers")
public class CashTransferController {

    private final CashTransferService service;

    public CashTransferController(CashTransferService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CASH.TRANSFER')")
    public CashTransferDto recordTransfer(@Valid @RequestBody RecordTransferRequest req) {
        return service.recordTransfer(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'cashtransfer','CASH.VIEW')")
    public CashTransferDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashTransferDto> listByCompany(@RequestParam Long companyId) {
        return service.listByCompany(companyId);
    }
}
