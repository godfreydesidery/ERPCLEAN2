package com.erp.api;

import com.erp.modules.purchases.domain.dto.CreateRfqRequest;
import com.erp.modules.purchases.domain.dto.RfqDto;
import com.erp.modules.purchases.service.RfqService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
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
 * Request for Quotation REST controller (ADR-0027 D-2, FR-PROC-05..08).
 */
@RestController
@RequestMapping("/api/v1/rfqs")
public class RfqController {

    private final RfqService service;

    public RfqController(RfqService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.RFQ.CREATE')")
    public ApiResponse<RfqDto> create(@RequestBody CreateRfqRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'rfq', 'PURCHASE.RFQ.VIEW')")
    public ApiResponse<RfqDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    @GetMapping
    @PreAuthorize("@perm.has('PURCHASE.RFQ.VIEW')")
    public ApiResponse<List<RfqDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<RfqDto> page = service.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Send RFQ to suppliers (DRAFT → SENT). */
    @PostMapping("/uid/{uid}/send")
    @PreAuthorize("@perm.scoped(#uid, 'rfq', 'PURCHASE.RFQ.CREATE')")
    public ApiResponse<RfqDto> send(@PathVariable String uid) {
        return ApiResponse.ok(service.send(uid));
    }

    /** Award RFQ to a supplier quote (SENT → AWARDED). Also creates PO from quote. */
    @PostMapping("/uid/{uid}/award")
    @PreAuthorize("@perm.scoped(#uid, 'rfq', 'PURCHASE.RFQ.AWARD')")
    public ApiResponse<RfqDto> award(
            @PathVariable String uid,
            @RequestParam String quoteUid) {
        return ApiResponse.ok(service.award(uid, quoteUid));
    }

    /** Cancel RFQ (any active state → CANCELLED). */
    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.scoped(#uid, 'rfq', 'PURCHASE.RFQ.CREATE')")
    public ApiResponse<RfqDto> cancel(@PathVariable String uid) {
        return ApiResponse.ok(service.cancel(uid));
    }
}
