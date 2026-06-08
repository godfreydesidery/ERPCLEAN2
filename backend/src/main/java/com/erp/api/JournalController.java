package com.erp.api;

import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.PostJournalRequest;
import com.erp.modules.gl.service.JournalService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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

    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public ApiResponse<List<JournalEntryDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<JournalEntryDto> page = service.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Post a reversing entry for a given journal entry uid (BR-GL-11). */
    @PostMapping("/uid/{uid}/reverse")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'journalentry','GL.POST')")
    public JournalEntryDto reverse(
            @PathVariable String uid,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate reversalDate) {
        return service.postManualReversal(uid, reversalDate);
    }
}
