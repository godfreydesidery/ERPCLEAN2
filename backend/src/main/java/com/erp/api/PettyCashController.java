package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CreatePettyCashFundRequest;
import com.erp.modules.cashbank.domain.dto.PettyCashFundDto;
import com.erp.modules.cashbank.domain.dto.PettyCashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordPettyCashTxnRequest;
import com.erp.modules.cashbank.domain.dto.UpdatePettyCashFundRequest;
import com.erp.modules.cashbank.service.PettyCashService;
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
 * Petty-cash imprest funds + movements (ADR-0050 D-7 PR-B).
 * Permission: PETTY_CASH.MANAGE for create/update/record; PETTY_CASH.VIEW for reads.
 * RECORD-ONLY — no GL posting this slice.
 */
@RestController
@RequestMapping("/api/v1/petty-cash/funds")
public class PettyCashController {

    private final PettyCashService service;

    public PettyCashController(PettyCashService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('PETTY_CASH.MANAGE')")
    public PettyCashFundDto createFund(@Valid @RequestBody CreatePettyCashFundRequest req) {
        return service.createFund(req);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pettycashfund','PETTY_CASH.MANAGE')")
    public PettyCashFundDto updateFund(@PathVariable String uid,
                                       @Valid @RequestBody UpdatePettyCashFundRequest req) {
        return service.updateFund(uid, req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pettycashfund','PETTY_CASH.VIEW')")
    public PettyCashFundDto getFund(@PathVariable String uid) {
        return service.getFund(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('PETTY_CASH.VIEW')")
    public List<PettyCashFundDto> listFunds(@RequestParam Long companyId) {
        return service.listFunds(companyId);
    }

    @PostMapping("/uid/{uid}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'pettycashfund','PETTY_CASH.MANAGE')")
    public PettyCashTransactionDto recordTransaction(@PathVariable String uid,
                                                      @Valid @RequestBody RecordPettyCashTxnRequest req) {
        return service.recordTransaction(uid, req);
    }

    @GetMapping("/uid/{uid}/transactions")
    @PreAuthorize("@perm.scoped(#uid,'pettycashfund','PETTY_CASH.VIEW')")
    public List<PettyCashTransactionDto> listTransactions(@PathVariable String uid) {
        return service.listTransactions(uid);
    }
}
