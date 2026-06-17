package com.erp.api;

import com.erp.modules.parties.domain.dto.CreatePaymentTermsRequest;
import com.erp.modules.parties.domain.dto.PaymentTermsDto;
import com.erp.modules.parties.domain.dto.UpdatePaymentTermsRequest;
import com.erp.modules.parties.service.PaymentTermsService;
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
 * PaymentTerms master administration (D-2, ADR-0040).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/payment-terms")
public class PaymentTermsController {

    private final PaymentTermsService paymentTermsService;

    public PaymentTermsController(PaymentTermsService paymentTermsService) {
        this.paymentTermsService = paymentTermsService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('PAYMENTTERMS.VIEW')")
    public ApiResponse<List<PaymentTermsDto>> list(@RequestParam Long companyId,
                                                    @RequestParam(required = false) String q,
                                                    Pageable pageable) {
        Page<PaymentTermsDto> page = paymentTermsService.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('PAYMENTTERMS.VIEW')")
    public PaymentTermsDto get(@PathVariable String uid) {
        return paymentTermsService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('PAYMENTTERMS.MANAGE')")
    public PaymentTermsDto create(@Valid @RequestBody CreatePaymentTermsRequest request) {
        return paymentTermsService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'paymentterms','PAYMENTTERMS.MANAGE')")
    public PaymentTermsDto update(@PathVariable String uid,
                                   @Valid @RequestBody UpdatePaymentTermsRequest request) {
        return paymentTermsService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'paymentterms','PAYMENTTERMS.MANAGE')")
    public void archive(@PathVariable String uid) {
        paymentTermsService.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'paymentterms','PAYMENTTERMS.MANAGE')")
    public void restore(@PathVariable String uid) {
        paymentTermsService.restoreByUid(uid);
    }
}
