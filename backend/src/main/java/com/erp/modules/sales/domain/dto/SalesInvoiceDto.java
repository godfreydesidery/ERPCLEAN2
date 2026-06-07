package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentType;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import java.math.BigDecimal;

/**
 * Full response DTO for a SalesInvoice header (ADR-0008 D-12).
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 * Cross-module IDs are included for the frontend to display names; enrichment fields
 * (customerName, agentName) are populated by the service, not stored on the header.
 */
public record SalesInvoiceDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        DocumentType documentType,
        String invoiceNumber,
        InvoiceStatus status,
        Long customerId,
        String customerName,
        Long agentId,
        String agentName,
        String currency,
        BigDecimal docDiscountAmount,
        BigDecimal docDiscountPercent,
        BigDecimal netTotalAmount,
        BigDecimal vatTotalAmount,
        BigDecimal grossTotalAmount,
        String taxSummary,
        String finalisedAt,
        Long finalisedBy,
        String voidedAt,
        Long voidedBy,
        String voidReason,
        String notes,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    /** Build from entity with enriched names. */
    public static SalesInvoiceDto from(SalesInvoice inv, String customerName, String agentName) {
        return new SalesInvoiceDto(
                inv.getId(),
                inv.getUid(),
                inv.getCompanyId(),
                inv.getBranchId(),
                inv.getDocumentType(),
                inv.getInvoiceNumber(),
                inv.getStatus(),
                inv.getCustomerId(),
                customerName,
                inv.getAgentId(),
                agentName,
                inv.getCurrency(),
                inv.getDocDiscountAmount(),
                inv.getDocDiscountPercent(),
                inv.getNetTotalAmount(),
                inv.getVatTotalAmount(),
                inv.getGrossTotalAmount(),
                inv.getTaxSummary(),
                inv.getFinalisedAt() != null ? inv.getFinalisedAt().toString() : null,
                inv.getFinalisedBy(),
                inv.getVoidedAt() != null ? inv.getVoidedAt().toString() : null,
                inv.getVoidedBy(),
                inv.getVoidReason(),
                inv.getNotes(),
                inv.getVersion(),
                inv.getCreatedAt() != null ? inv.getCreatedAt().toString() : null,
                inv.getCreatedBy(),
                inv.getUpdatedAt() != null ? inv.getUpdatedAt().toString() : null,
                inv.getUpdatedBy()
        );
    }
}
