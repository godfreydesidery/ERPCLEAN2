package com.erp.api;

import com.erp.modules.gl.domain.dto.GlPostingExceptionDto;
import com.erp.modules.gl.domain.dto.GlPostingRepostRequest;
import com.erp.modules.gl.domain.dto.GlPostingRepostResultDto;
import com.erp.modules.gl.domain.dto.GlSalesTieOutDto;
import com.erp.modules.gl.service.GlPostingExceptionService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GL posting exceptions (ACC-02): automatic postings (sales, COGS, goods receipts, reversals…) that
 * failed and were swallowed so the business document could stand. Listing needs GL.VIEW;
 * re-posting writes a journal, so it needs GL.POST — the same codes as the journal screens. The
 * service scopes both to the company (assertCanActIn) and finds the exception company-scoped.
 */
@RestController
@RequestMapping("/api/v1/gl/posting-exceptions")
public class GlPostingExceptionController {

    private final GlPostingExceptionService service;

    public GlPostingExceptionController(GlPostingExceptionService service) {
        this.service = service;
    }

    /**
     * Posting exceptions of a company, newest first. {@code from}/{@code to} filter on the posting
     * date that was attempted; {@code includeResolved=false} (default) lists only open ones.
     */
    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public ApiResponse<List<GlPostingExceptionDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(defaultValue = "false") boolean includeResolved,
            Pageable pageable) {
        Page<GlPostingExceptionDto> page =
                service.list(companyId, sourceType, from, to, includeResolved, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * Sales-vs-GL revenue and VAT tie-out for a date range (defaults to the current month).
     * GL.VIEW only: it discloses two aggregate totals per side, nothing per document.
     */
    @GetMapping("/sales-tie-out")
    @PreAuthorize("@perm.has('GL.VIEW')")
    public GlSalesTieOutDto salesTieOut(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to) {
        return service.salesTieOut(companyId, from, to);
    }

    /**
     * Re-posts one exception through the poster that failed, exactly once. Body optional:
     * {@code postingDate} posts on another date when the original period is closed for good.
     */
    @PostMapping("/uid/{uid}/repost")
    @PreAuthorize("@perm.has('GL.POST')")
    public GlPostingRepostResultDto repost(@PathVariable String uid,
                                           @RequestParam Long companyId,
                                           @RequestBody(required = false)
                                           GlPostingRepostRequest body) {
        return service.repost(companyId, uid, body != null ? body.postingDate() : null);
    }
}
