package com.erp.api;

import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalSearchCriteria;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import com.erp.modules.gl.domain.dto.PostJournalRequest;
import com.erp.modules.gl.domain.dto.ReversalRequest;
import com.erp.modules.gl.service.JournalService;
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
 * Manual journal entry controller (ADR-0013, FR-GL-06/11).
 * Permission codes seeded in V10__general_ledger.sql: GL.VIEW, GL.POST.
 */
@RestController
@RequestMapping("/api/v1/gl/journals")
public class JournalController {

    private final JournalService service;

    public JournalController(JournalService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','GL.POST')")
    public JournalEntryDto post(@Valid @RequestBody PostJournalRequest req) {
        return service.postManual(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'journalentry','GL.VIEW')")
    public JournalEntryDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /**
     * Journal list. All filters are optional (ACC-19): posting-date range, source type, an account
     * (journals with a line on it) and free text over description, batch number, refs and line
     * memos. Rows carry {@code documentRef}, the source document's number when it can be read.
     */
    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public ApiResponse<List<JournalEntryDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false) JournalSourceType sourceType,
            @RequestParam(required = false) String accountUid,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        Page<JournalEntryDto> page = service.search(companyId,
                new JournalSearchCriteria(from, to, sourceType, accountUid, q), pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * Post a reversing entry for a given journal entry uid (BR-GL-11, issue #30).
     *
     * <p>Accepts an optional JSON body with {@code reversalDate} (business date for the reversing
     * entry; defaults to today if absent) and {@code reason} (audit-trail text appended to the
     * reversing entry's description). Body may be omitted entirely for backwards compatibility.
     */
    @PostMapping("/uid/{uid}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'journalentry','GL.POST')")
    public JournalEntryDto reverse(
            @PathVariable String uid,
            @RequestBody(required = false) ReversalRequest body) {
        java.time.LocalDate reversalDate = body != null ? body.reversalDate() : null;
        String reason = body != null ? body.reason() : null;
        return service.postManualReversal(uid, reversalDate, reason);
    }
}
