package com.erp.api;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.service.ApDebitNoteService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
 * AP debit notes (ADR-0015 D-2f/D-6/D-12).
 * Raises a debit note; reduces outstanding on the linked bill (if provided);
 * posts DR AP / CR Purchases [+ CR VAT_PAYABLE] synchronously.
 * Permission: AP.DEBITNOTE.
 */
@RestController
@RequestMapping("/api/v1/ap/debit-notes")
public class ApDebitNoteController {

    private final ApDebitNoteService service;

    public ApDebitNoteController(ApDebitNoteService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AP.DEBITNOTE')")
    public ApDebitNoteDto raise(@Valid @RequestBody RaiseDebitNoteRequest req) {
        return service.raise(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'apdebitnote','AP.VIEW')")
    public ApDebitNoteDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApiResponse<List<ApDebitNoteDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long supplierId,
            Pageable pageable) {
        Page<ApDebitNoteDto> page = supplierId != null
                ? service.listBySupplier(companyId, supplierId, pageable)
                : service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
