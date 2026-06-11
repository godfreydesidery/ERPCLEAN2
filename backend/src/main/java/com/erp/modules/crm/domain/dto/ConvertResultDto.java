package com.erp.modules.crm.domain.dto;

/** Result of converting an opportunity to a quote or SO (ADR-0031 D-7). */
public record ConvertResultDto(
        String opportunityUid,
        String convertedDocumentKind,
        String convertedDocumentUid,
        String convertedDocumentNumber
) {}
