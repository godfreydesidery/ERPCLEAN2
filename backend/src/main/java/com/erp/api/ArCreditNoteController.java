package com.erp.api;

import com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.service.ArCreditNoteService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR credit note endpoints (ADR-0014, FR-AR-14, OQ-AR-04).
 * Permission code seeded in V11__accounts_receivable.sql: AR.CREDITNOTE.
 */
@RestController
@RequestMapping("/api/v1/ar/credit-notes")
public class ArCreditNoteController {

    private final ArCreditNoteService service;

    public ArCreditNoteController(ArCreditNoteService service) {
        this.service = service;
    }

    /**
     * Raise a standalone credit note. Scoped on the request body's companyUid.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','AR.CREDITNOTE')")
    public ArCreditNoteDto raise(@Valid @RequestBody RaiseCreditNoteRequest req) {
        return service.raise(req);
    }

    /**
     * Apply a credit note to one or more open invoices (ADR-0040 D-6).
     * Posts nothing to GL except a realized-FX plug when rates differ.
     * Uses the existing AR.CREDITNOTE permission.
     */
    @PostMapping("/uid/{uid}/apply")
    @PreAuthorize("@perm.has('AR.CREDITNOTE')")
    public ArCreditNoteDto apply(@PathVariable String uid,
                                  @Valid @RequestBody ApplyCreditNoteRequest req) {
        return service.apply(new ApplyCreditNoteRequest(uid, req.allocations()));
    }

    /**
     * Replace the current allocation set (delete-and-reinsert). No GL change except FX plug.
     */
    @PutMapping("/uid/{uid}/apply")
    @PreAuthorize("@perm.has('AR.CREDITNOTE')")
    public ArCreditNoteDto reapply(@PathVariable String uid,
                                    @Valid @RequestBody ApplyCreditNoteRequest req) {
        return service.reapply(new ApplyCreditNoteRequest(uid, req.allocations()));
    }

    /** Single credit note by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ArCreditNoteDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /** Paged list by company. */
    @GetMapping
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ApiResponse<List<ArCreditNoteDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<ArCreditNoteDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
