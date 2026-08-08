package com.erp.modules.documents.domain.dto;

import com.erp.modules.documents.domain.entity.GeneratedDocument;
import com.erp.modules.documents.domain.enums.DocumentType;
import java.time.Instant;

/** Response DTO for a generated-documents render log row (ADR-0023 D-4). */
public record GeneratedDocumentDto(
        Long         id,
        String       uid,
        Long         companyId,
        Long         branchId,
        String       documentNumber,
        DocumentType documentType,
        String       sourceType,
        String       sourceUid,
        String       sourceParams,
        Long         brandingId,
        String       contentHash,
        Integer      byteSize,
        String       mimeType,
        Long         generatedBy,
        Instant      generatedAt,
        /**
         * Convenience link for the download endpoint. MUST stay byte-identical to the route
         * {@code DocumentController.download} publishes — see {@link #DOWNLOAD_PATH_PREFIX}.
         */
        String       downloadUrl
) {
    /**
     * The uid-addressed download route, as mapped by
     * {@code DocumentController} ({@code @GetMapping("/uid/{uid}/download")} under
     * {@code /api/v1/documents}).
     *
     * <p>This DTO previously advertised {@code /api/v1/documents/{uid}/download} — one segment
     * short of the real route — so every {@code downloadUrl} handed to an API client 404'd, for
     * every document type. The web app builds its own URL and never noticed; the POS app and any
     * integrator following the response did. The route is NOT changed to match the DTO: callers
     * already use the real path, and moving it would break them.
     */
    private static final String DOWNLOAD_PATH_PREFIX = "/api/v1/documents/uid/";

    /** Suffix of the same route. */
    private static final String DOWNLOAD_PATH_SUFFIX = "/download";

    public static GeneratedDocumentDto from(GeneratedDocument g) {
        return new GeneratedDocumentDto(
                g.getId(), g.getUid(), g.getCompanyId(), g.getBranchId(),
                g.getDocumentNumber(), g.getDocumentType(),
                g.getSourceType(), g.getSourceUid(), g.getSourceParams(),
                g.getBrandingId(), g.getContentHash(), g.getByteSize(),
                g.getMimeType(), g.getGeneratedBy(), g.getGeneratedAt(),
                downloadUrlFor(g.getUid()));
    }

    /** The download link for a generated document, matching the controller route exactly. */
    public static String downloadUrlFor(String uid) {
        return DOWNLOAD_PATH_PREFIX + uid + DOWNLOAD_PATH_SUFFIX;
    }
}
