package com.erp.api;

import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.CreateQuotationRequest;
import com.erp.modules.sales.domain.dto.QuotationDto;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.service.QuotationService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Quotation lifecycle REST controller (ADR-0021 D-3).
 * Responses auto-wrapped by ApiResponseAdvice.
 * Permission codes seeded in V18__sales_orders.sql.
 */
@RestController
@RequestMapping("/api/v1/quotations")
public class QuotationController {

    private final QuotationService quotationService;

    public QuotationController(QuotationService quotationService) {
        this.quotationService = quotationService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid(),'company','SALES.QUOTE.CREATE')")
    public QuotationDto create(@Valid @RequestBody CreateQuotationRequest request) {
        return quotationService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.VIEW')")
    public QuotationDto getByUid(@PathVariable String uid) {
        return quotationService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.QUOTE.VIEW')")
    public ApiResponse<List<QuotationDto>> list(@RequestParam Long companyId,
                                                 Pageable pageable) {
        Page<QuotationDto> page = quotationService.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.VIEW')")
    public List<QuotationLineDto> listLines(@PathVariable String uid) {
        return quotationService.listLines(uid);
    }

    @PostMapping("/uid/{uid}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.CREATE')")
    public QuotationLineDto addLine(@PathVariable String uid,
                                    @Valid @RequestBody AddQuotationLineRequest request) {
        return quotationService.addLine(uid, request);
    }

    @DeleteMapping("/uid/{uid}/lines/{lineUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.CREATE')")
    public void removeLine(@PathVariable String uid, @PathVariable String lineUid) {
        quotationService.removeLine(uid, lineUid);
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    @PutMapping("/uid/{uid}/send")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.SEND')")
    public void send(@PathVariable String uid) {
        quotationService.send(uid);
    }

    @PutMapping("/uid/{uid}/accept")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.ACCEPT')")
    public SalesOrderDto accept(@PathVariable String uid) {
        return quotationService.accept(uid);
    }

    @PutMapping("/uid/{uid}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'quotation','SALES.QUOTE.ACCEPT')")
    public void reject(@PathVariable String uid) {
        quotationService.reject(uid);
    }
}
