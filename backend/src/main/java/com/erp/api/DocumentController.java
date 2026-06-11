package com.erp.api;

import com.erp.modules.documents.domain.dto.GeneratedDocumentDto;
import com.erp.modules.documents.domain.dto.RenderDocumentRequest;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.service.DocumentRenderService;
import com.erp.platform.security.RequestContext;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Document render, download, and log endpoints (ADR-0023 D-11, FR-DOC-06/08/09/15/16).
 * Permissions: DOCUMENT.RENDER / DOCUMENT.VIEW.
 * Every response is wrapped by ApiResponseAdvice except download (ResponseEntity<byte[]>).
 */
@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentRenderService renderService;

    public DocumentController(DocumentRenderService renderService) {
        this.renderService = renderService;
    }

    /**
     * Render a document on demand — returns the GeneratedDocumentDto (record + download link).
     * POST /api/v1/documents/render
     */
    @PostMapping("/render")
    @PreAuthorize("@perm.has('DOCUMENT.RENDER')")
    public GeneratedDocumentDto render(@RequestBody RenderDocumentRequest req) {
        return renderService.render(req);
    }

    /**
     * Convenience GET render + stream (inline bytes). Scope check on source inside service.
     * GET /api/v1/documents/render?type=INVOICE&source=<uid>
     */
    @GetMapping("/render")
    @PreAuthorize("@perm.has('DOCUMENT.RENDER')")
    public ResponseEntity<byte[]> renderInline(
            @RequestParam DocumentType type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String params) {
        RenderDocumentRequest req = new RenderDocumentRequest(type, source, params);
        GeneratedDocumentDto dto = renderService.render(req);
        byte[] bytes = renderService.download(dto.uid());
        String filename = dto.documentType().name().toLowerCase() + "-" + dto.documentNumber() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }

    /**
     * Download a previously rendered document (re-renders from live source).
     * GET /api/v1/documents/{uid}/download
     */
    @GetMapping("/{uid}/download")
    @PreAuthorize("@perm.scoped(#uid, 'generateddocument', 'DOCUMENT.RENDER')")
    public ResponseEntity<byte[]> download(@PathVariable String uid) {
        GeneratedDocumentDto meta = renderService.getByUid(uid);
        byte[] bytes = renderService.download(uid);
        String filename = meta.documentType().name().toLowerCase()
                + "-" + meta.documentNumber() + ".pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }

    /**
     * List the generated-documents log.
     * GET /api/v1/documents?type=&sourceUid=&from=&to=
     */
    @GetMapping
    @PreAuthorize("@perm.has('DOCUMENT.VIEW')")
    public Page<GeneratedDocumentDto> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) DocumentType type,
            @RequestParam(required = false) String sourceUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            Pageable pageable) {
        return renderService.list(companyId, type, sourceUid, from, to, pageable);
    }

    /**
     * Get a single log record by uid.
     * GET /api/v1/documents/{uid}
     */
    @GetMapping("/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'generateddocument', 'DOCUMENT.VIEW')")
    public GeneratedDocumentDto getByUid(@PathVariable String uid) {
        return renderService.getByUid(uid);
    }
}
