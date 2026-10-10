package com.erp.api;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.service.ApPaymentService;
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
 * AP payment: single bill settlement + payment runs (ADR-0015 D-3/D-4/D-5/D-12).
 * Both endpoints post DR AP / CR Cash to GL synchronously; GL failure rolls back atomically.
 * Permission: AP.PAYMENT.RUN.
 */
@RestController
@RequestMapping("/api/v1/ap/payments")
public class ApPaymentController {

    private final ApPaymentService service;
    private final com.erp.modules.ap.service.ApPaymentReversalService reversalService;

    public ApPaymentController(ApPaymentService service,
                               com.erp.modules.ap.service.ApPaymentReversalService reversalService) {
        this.service = service;
        this.reversalService = reversalService;
    }

    @PostMapping("/single")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AP.PAYMENT.RUN')")
    public ApPaymentDto paySingle(@Valid @RequestBody PaySingleBillRequest req) {
        return service.paySingle(req);
    }

    @PostMapping("/payment-run")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AP.PAYMENT.RUN')")
    public ApPaymentDto paymentRun(@Valid @RequestBody PaymentRunRequest req) {
        return service.paymentRun(req);
    }

    /**
     * Reverse a posted supplier payment (AP-03): posts the reversing journal, writes the opposite
     * cash-book row and restores the bills it settled. Gated by AP.PAYMENT.REVERSE and scoped to
     * the payment's company. The reason is required.
     */
    @PostMapping("/uid/{uid}/reverse")
    @PreAuthorize("@perm.scoped(#uid,'appayment','AP.PAYMENT.REVERSE')")
    public ApPaymentDto reverse(@PathVariable String uid,
                                @Valid @RequestBody com.erp.modules.ap.domain.dto.ReversePaymentRequest req) {
        return reversalService.reverse(uid, req.reason());
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'appayment','AP.VIEW')")
    public ApPaymentDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('AP.VIEW')")
    public ApiResponse<List<ApPaymentDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long supplierId,
            Pageable pageable) {
        Page<ApPaymentDto> page = supplierId != null
                ? service.listBySupplier(companyId, supplierId, pageable)
                : service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
