package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashAccountOptionDto;
import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashTillOptionDto;
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

    /**
     * Till picker for the end-of-day cash count (LRB-03 / ADM-01): ACTIVE CASH-type accounts only,
     * as a narrow row with no balances or bank detail. Admits the cash-count codes so a cashier can
     * pick the drawer they are counting without being granted CASH.VIEW (which exposes every bank
     * account). The service asserts the company is the caller's own.
     */
    @GetMapping("/tills")
    @PreAuthorize("@perm.has('CASH.VIEW') or @perm.has('CASH.COUNT.MANAGE') "
            + "or @perm.has('CASH.COUNT.VIEW')")
    public List<CashTillOptionDto> listCashTills(@RequestParam Long companyId) {
        return service.listCashTills(companyId);
    }

    /**
     * Account picker for "which cash / bank / M-Pesa account did this money land in" (ARC-05):
     * ACTIVE accounts of every type, as a narrow row with no balances or bank detail. Admits the
     * receipt-recording and cash-entry codes so the Record Receipt screen can offer it to a cashier
     * without granting CASH.VIEW. The service asserts the company is the caller's own.
     */
    @GetMapping("/options")
    @PreAuthorize("@perm.has('CASH.VIEW') or @perm.has('AR.RECEIPT.RECORD') "
            + "or @perm.has('CASH.ENTRY.RECORD')")
    public List<CashAccountOptionDto> listAccountOptions(@RequestParam Long companyId) {
        return service.listAccountOptions(companyId);
    }
}
