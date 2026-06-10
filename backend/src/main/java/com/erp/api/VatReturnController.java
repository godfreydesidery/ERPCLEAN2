package com.erp.api;

import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.service.VatReturnService;
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
 * VAT return lifecycle endpoints (ADR-0017 D-4, FR-VAT-01/02/08).
 * Permissions seeded in V14: VAT.RETURN.PREPARE, VAT.RETURN.FILE, VAT.VIEW.
 */
@RestController
@RequestMapping("/api/v1/vat/returns")
public class VatReturnController {

    private final VatReturnService service;

    public VatReturnController(VatReturnService service) {
        this.service = service;
    }

    /** Open a new DRAFT VAT return for the given company-month (FR-VAT-01). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','VAT.RETURN.PREPARE')")
    public VatReturnDto open(@Valid @RequestBody OpenVatReturnRequest req) {
        return service.open(req);
    }

    /** Recompute a DRAFT return (FR-VAT-02). */
    @PostMapping("/uid/{uid}/recompute")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.RETURN.PREPARE')")
    public VatReturnDto recompute(@PathVariable String uid) {
        return service.recompute(uid);
    }

    /** File (lock) a DRAFT return — freeze + GL settlement (FR-VAT-08). */
    @PostMapping("/uid/{uid}/file")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.RETURN.FILE')")
    public VatReturnDto file(@PathVariable String uid,
                              @Valid @RequestBody FileVatReturnRequest req) {
        return service.file(uid, req);
    }

    /** Single return by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.VIEW')")
    public VatReturnDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /** Paged list by company. */
    @GetMapping
    @PreAuthorize("@perm.has('VAT.VIEW')")
    public ApiResponse<List<VatReturnDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<VatReturnDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
