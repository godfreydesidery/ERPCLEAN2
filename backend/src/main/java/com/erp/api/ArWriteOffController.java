package com.erp.api;

import com.erp.modules.ar.domain.dto.ArWriteOffDto;
import com.erp.modules.ar.domain.dto.WriteOffRequest;
import com.erp.modules.ar.service.ArWriteOffService;
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
 * AR write-off endpoints (ADR-0014, FR-AR-13).
 * Permission code seeded in V11__accounts_receivable.sql: AR.WRITEOFF.
 */
@RestController
@RequestMapping("/api/v1/ar/write-offs")
public class ArWriteOffController {

    private final ArWriteOffService service;

    public ArWriteOffController(ArWriteOffService service) {
        this.service = service;
    }

    /**
     * Write off an uncollectable open item. Scoped on the invoice uid — resolves the owning
     * company from the ArInvoice row (service does assertCanActIn internally).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.arInvoiceUid,'arinvoice','AR.WRITEOFF')")
    public ArWriteOffDto writeOff(@Valid @RequestBody WriteOffRequest req) {
        return service.writeOff(req);
    }

    /** Single write-off by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ArWriteOffDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /** Paged list by company. */
    @GetMapping
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ApiResponse<List<ArWriteOffDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<ArWriteOffDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
