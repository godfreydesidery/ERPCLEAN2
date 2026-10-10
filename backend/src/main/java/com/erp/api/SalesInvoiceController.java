package com.erp.api;

import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.OverrideLinePriceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.dto.SalesInvoicePaymentDto;
import com.erp.modules.sales.domain.dto.UpdateInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
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
 * Sales invoice lifecycle REST controller (ADR-0008, FR-SALES-01..25).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Gates use exact permission codes seeded in V5__sales.sql §8.
 *
 * <p>Permission codes (V5 seeded):
 * SALES.INVOICE.VIEW, SALES.INVOICE.CREATE (covers draft/lines/finalise),
 * SALES.INVOICE.SETTLE (payments), SALES.INVOICE.VOID, TAXRATE.VIEW, TAXRATE.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/sales-invoices")
public class SalesInvoiceController {

    private final SalesInvoiceService salesInvoiceService;

    public SalesInvoiceController(SalesInvoiceService salesInvoiceService) {
        this.salesInvoiceService = salesInvoiceService;
    }

    // -------------------------------------------------------------------------
    // Invoice CRUD + lifecycle
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid,'company','SALES.INVOICE.CREATE')")
    public SalesInvoiceDto create(@Valid @RequestBody CreateSalesInvoiceRequest request) {
        return salesInvoiceService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.VIEW')")
    public SalesInvoiceDto getByUid(@PathVariable String uid) {
        return salesInvoiceService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public ApiResponse<List<SalesInvoiceDto>> list(@RequestParam Long companyId,
                                                    @RequestParam(required = false) String q,
                                                    @RequestParam(required = false) String status,
                                                    // The till's "Today's sales": POS sales of one
                                                    // branch finalised since this instant (the
                                                    // till's own local midnight), newest first.
                                                    @RequestParam(required = false) Instant finalisedFrom,
                                                    @RequestParam(required = false) Long branchId,
                                                    // SAL-10: optional creation-date window.
                                                    @RequestParam(required = false) LocalDate dateFrom,
                                                    @RequestParam(required = false) LocalDate dateTo,
                                                    Pageable pageable) {
        Page<SalesInvoiceDto> page;
        if (finalisedFrom != null) {
            if (branchId == null) {
                throw new IllegalArgumentException("Choose a branch to list its sales.");
            }
            page = salesInvoiceService.listPosSalesSince(companyId, branchId, finalisedFrom, pageable);
        } else {
            // SAL-10: status used to be accepted and ignored; q now also matches customer names.
            page = salesInvoiceService.list(companyId, q, status, dateFrom, dateTo, pageable);
        }
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PutMapping("/uid/{uid}/finalize")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')")
    public void finalise(@PathVariable String uid,
                         @Valid @RequestBody FinaliseInvoiceRequest request) {
        salesInvoiceService.finalise(uid, request);
    }

    @PutMapping("/uid/{uid}/void")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.VOID')")
    public void voidInvoice(@PathVariable String uid,
                            @Valid @RequestBody VoidInvoiceRequest request) {
        salesInvoiceService.voidInvoice(uid, request);
    }

    /**
     * SAL-13 / LSF-17: cancel (discard) an abandoned DRAFT invoice. Drafts carry no number and no
     * postings, so this is a plain removal — gated like every other draft edit. A finalised
     * invoice is refused (409); it is voided instead.
     */
    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')")
    public void cancelDraft(@PathVariable String uid,
                            @RequestParam(required = false) String reason) {
        salesInvoiceService.cancelDraft(uid, reason);
    }

    // -------------------------------------------------------------------------
    // Lines
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.VIEW')")
    public List<SalesInvoiceLineDto> listLines(@PathVariable String uid) {
        return salesInvoiceService.listLines(uid);
    }

    @PostMapping("/uid/{uid}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')")
    public SalesInvoiceLineDto addLine(@PathVariable String uid,
                                       @Valid @RequestBody AddInvoiceLineRequest request) {
        return salesInvoiceService.addLine(uid, request);
    }

    /**
     * SAL-12: change a DRAFT line's quantity and/or discount ("make it 3, not 2") without removing
     * and re-adding it. Product and unit stay fixed; the discount ceiling is enforced exactly as on
     * add. Gated like every other draft edit.
     */
    @PutMapping("/uid/{uid}/lines/{lineUid}")
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')")
    public SalesInvoiceLineDto updateLine(@PathVariable String uid, @PathVariable String lineUid,
                                          @Valid @RequestBody UpdateInvoiceLineRequest request) {
        return salesInvoiceService.updateLine(uid, lineUid, request);
    }

    @DeleteMapping("/uid/{uid}/lines/{lineUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')")
    public void removeLine(@PathVariable String uid, @PathVariable String lineUid) {
        salesInvoiceService.removeLine(uid, lineUid);
    }

    /** Override a draft line's unit price (FR-SALES-08, BR-SALES-09) — requires SALES.INVOICE.OVERRIDE. */
    @PostMapping("/uid/{uid}/lines/uid/{lineUid}/override-price")
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.OVERRIDE')")
    public SalesInvoiceLineDto overrideLinePrice(@PathVariable String uid, @PathVariable String lineUid,
                                                 @Valid @RequestBody OverrideLinePriceRequest request) {
        return salesInvoiceService.overrideLinePrice(uid, lineUid, request);
    }

    // -------------------------------------------------------------------------
    // Payments
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/payments")
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.VIEW')")
    public List<SalesInvoicePaymentDto> listPayments(@PathVariable String uid) {
        return salesInvoiceService.listPayments(uid);
    }

    @PostMapping("/uid/{uid}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.SETTLE')")
    public SalesInvoicePaymentDto addPayment(@PathVariable String uid,
                                              @Valid @RequestBody AddPaymentRequest request) {
        return salesInvoiceService.addPayment(uid, request);
    }

    @DeleteMapping("/uid/{uid}/payments/{paymentUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'invoice','SALES.INVOICE.CREATE')")
    public void removePayment(@PathVariable String uid, @PathVariable String paymentUid) {
        salesInvoiceService.removePayment(uid, paymentUid);
    }
}
