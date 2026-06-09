package com.erp.api;

import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.service.SupplierBillService;
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
 * AP supplier bill entry + read (ADR-0015 D-1/D-2a/D-12).
 * Creation: POST creates a DRAFT bill (no GL posting — posting happens on match).
 * Responses auto-wrapped by {@code ApiResponseAdvice}.
 * Permissions: AP.BILL.ENTER (enter), AP.VIEW (list/get).
 */
@RestController
@RequestMapping("/api/v1/ap/supplier-bills")
public class SupplierBillController {

    private final SupplierBillService service;

    public SupplierBillController(SupplierBillService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AP.BILL.ENTER')")
    public SupplierBillDto enter(@Valid @RequestBody EnterBillRequest req) {
        return service.enterBill(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'supplierbill','AP.VIEW')")
    public SupplierBillDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApiResponse<List<SupplierBillDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long supplierId,
            Pageable pageable) {
        Page<SupplierBillDto> page = supplierId != null
                ? service.listBySupplier(companyId, supplierId, pageable)
                : service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
