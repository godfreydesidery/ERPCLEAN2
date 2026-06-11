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
        /** Convenience link for the download endpoint. */
        String       downloadUrl
) {
    public static GeneratedDocumentDto from(GeneratedDocument g) {
        return new GeneratedDocumentDto(
                g.getId(), g.getUid(), g.getCompanyId(), g.getBranchId(),
                g.getDocumentNumber(), g.getDocumentType(),
                g.getSourceType(), g.getSourceUid(), g.getSourceParams(),
                g.getBrandingId(), g.getContentHash(), g.getByteSize(),
                g.getMimeType(), g.getGeneratedBy(), g.getGeneratedAt(),
                "/api/v1/documents/" + g.getUid() + "/download");
    }
}
