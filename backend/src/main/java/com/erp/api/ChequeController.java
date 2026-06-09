package com.erp.api;

import com.erp.modules.cashbank.domain.dto.ChequeDto;
import com.erp.modules.cashbank.domain.dto.RegisterChequeRequest;
import com.erp.modules.cashbank.service.ChequeService;
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
 * Cheque register — BANK accounts only (ADR-0016 D-5, FR-CASH-10/11, BR-CASH-11/12).
 * Lifecycle: ISSUED → CLEARED or CANCELLED. GL effect rides the settled payment.
 * Permission: CHEQUE.MANAGE for writes; CASH.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/cash/cheques")
public class ChequeController {

    private final ChequeService service;

    public ChequeController(ChequeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CHEQUE.MANAGE')")
    public ChequeDto register(@Valid @RequestBody RegisterChequeRequest req) {
        return service.register(req);
    }

    @PostMapping("/uid/{uid}/clear")
    @PreAuthorize("@perm.scoped(#uid,'cheque','CHEQUE.MANAGE')")
    public ChequeDto clear(@PathVariable String uid) {
        return service.clear(uid);
    }

    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.scoped(#uid,'cheque','CHEQUE.MANAGE')")
    public ChequeDto cancel(@PathVariable String uid) {
        return service.cancel(uid);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'cheque','CASH.VIEW')")
    public ChequeDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('CASH.VIEW')")
    public List<ChequeDto> listByCompany(@RequestParam Long companyId) {
        return service.listByCompany(companyId);
    }
}
