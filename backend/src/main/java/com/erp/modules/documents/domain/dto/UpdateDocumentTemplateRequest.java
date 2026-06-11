package com.erp.modules.documents.domain.dto;

import com.erp.platform.common.domain.MasterStatus;

/** Request DTO to update a document template registry row (toggle active/inactive, set title). */
public record UpdateDocumentTemplateRequest(
        String       title,
        MasterStatus status,
        Long         brandingId,
        Long         version
) {}
