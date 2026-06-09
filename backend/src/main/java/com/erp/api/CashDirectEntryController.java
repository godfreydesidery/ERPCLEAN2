package com.erp.api;

import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.service.CashDirectEntryService;
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
 * Direct cash/bank entries not tied to AR/AP (ADR-0016 D-4, FR-CASH-09, BR-CASH-05).
 * Permission: CASH.ENTRY.RECORD for writes; CASH.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/cash/entries")
public class CashDirectEntryController {

    private final CashDirectEntryService service;

    public CashDirectEntryController(CashDirectEntryService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CASH.ENTRY.RECORD')")
    public CashTransactionDto recordDirectEntry(@Valid @RequestBody RecordDirectEntryRequest req) {
        return service.recordDirectEntry(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'cashtransaction','CASH.VIEW')")
    public CashTransactionDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<CashTransactionDto> listByAccount(@RequestParam Long companyId,
                                                   @RequestParam Long accountId) {
        return service.listByAccount(companyId, accountId);
    }
}
