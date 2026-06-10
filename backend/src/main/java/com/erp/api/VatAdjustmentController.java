package com.erp.api;

import com.erp.modules.tax.domain.dto.AddVatAdjustmentRequest;
import com.erp.modules.tax.domain.dto.VatAdjustmentDto;
import com.erp.modules.tax.service.VatAdjustmentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * VAT adjustment endpoints (ADR-0017 D-3, FR-VAT-05/06, BR-VAT-09).
 * Permissions seeded in V14: VAT.ADJUST, VAT.VIEW.
 */
@RestController
@RequestMapping("/api/v1/vat/returns/uid/{returnUid}/adjustments")
public class VatAdjustmentController {

    private final VatAdjustmentService service;

    public VatAdjustmentController(VatAdjustmentService service) {
        this.service = service;
    }

    /** Add an adjustment to a DRAFT VAT return. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#returnUid,'vatreturn','VAT.ADJUST')")
    public VatAdjustmentDto add(@PathVariable String returnUid,
                                 @Valid @RequestBody AddVatAdjustmentRequest req) {
        return service.addAdjustment(returnUid, req);
    }

    /** Remove an adjustment from a DRAFT VAT return. */
    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#returnUid,'vatreturn','VAT.ADJUST')")
    public void remove(@PathVariable String returnUid, @PathVariable String uid) {
        service.removeAdjustment(returnUid, uid);
    }

    /** List all adjustments on a return. */
    @GetMapping
    @PreAuthorize("@perm.scoped(#returnUid,'vatreturn','VAT.VIEW')")
    public List<VatAdjustmentDto> list(@PathVariable String returnUid) {
        return service.listByReturn(returnUid);
    }
}
