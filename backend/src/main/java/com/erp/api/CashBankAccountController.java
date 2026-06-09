package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.UpdateCashBankAccountRequest;
import com.erp.modules.cashbank.service.CashBankAccountService;
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
 * Cash/bank account management (ADR-0016 D-2a, FR-CASH-01..07).
 * Permission: CASH.ACCOUNT.MANAGE for writes; CASH.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/cash/accounts")
public class CashBankAccountController {

    private final CashBankAccountService service;

    public CashBankAccountController(CashBankAccountService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CASH.ACCOUNT.MANAGE')")
    public CashBankAccountDto create(@Valid @RequestBody CreateCashBankAccountRequest req) {
        return service.create(req);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.ACCOUNT.MANAGE')")
    public CashBankAccountDto update(@PathVariable String uid,
                                     @Valid @RequestBody UpdateCashBankAccountRequest req) {
        return service.update(uid, req);
    }

    @PostMapping("/uid/{uid}/set-default")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.ACCOUNT.MANAGE')")
    public CashBankAccountDto setDefault(@PathVariable String uid) {
        return service.setDefault(uid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'cashbankaccount','CASH.VIEW')")
    public CashBankAccountDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashBankAccountDto> listByCompany(@RequestParam Long companyId) {
        return service.listByCompany(companyId);
    }
}
