package com.erp.modules.documents.domain.dto;

import com.erp.modules.documents.domain.enums.DocumentType;
import java.time.Instant;

/**
 * Outbox event payload for DOCUMENT.GENERATED (ADR-0023 D-7).
 * Producer: DocumentRenderService. Consumer: future Notifications enabler (X.2).
 * Carries NO bytes — the consumer fetches via /api/documents/{uid}/download or re-renders.
 */
public record DocumentGeneratedPayload(
        String       documentUid,
        DocumentType documentType,
        String       sourceType,
        String       sourceUid,
        Long         companyId,
        Long         branchId,
        Long         generatedBy,
        Instant      generatedAt
) {}
