package com.erp.modules.documents.domain.dto;

import com.erp.modules.documents.domain.entity.DocumentTemplate;
import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.domain.enums.RendererKey;
import com.erp.platform.common.domain.MasterStatus;

/** Response DTO for a document template registry row (ADR-0023 D-3). */
public record DocumentTemplateDto(
        Long         id,
        String       uid,
        Long         companyId,
        DocumentType documentType,
        RendererKey  rendererKey,
        Long         brandingId,
        String       title,
        MasterStatus status
) {
    public static DocumentTemplateDto from(DocumentTemplate t) {
        return new DocumentTemplateDto(
                t.getId(), t.getUid(), t.getCompanyId(),
                t.getDocumentType(), t.getRendererKey(),
                t.getBrandingId(), t.getTitle(), t.getStatus());
    }
}
