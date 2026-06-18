package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentType;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import java.math.BigDecimal;

/**
 * Full response DTO for a SalesInvoice header (ADR-0008 D-12).
 * Carries both {@code id} (JSON string via global Long-as-string config) and {@code uid}.
 * Cross-module IDs are included for the frontend to display names; enrichment fields
 * (customerName, agentName, routeUid/routeCode/routeName) are populated by the service.
 * Route fields are nullable (BR-ROUTE-05 — a sale is never blocked when the route is blank).
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
        String customerPoNumber,
        Long paymentTermsId,
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
        Long shipToAddressId,
        Long billToAddressId,
        String shipToAddressText,
        String billToAddressText,
        // Route reference — nullable (ADR-0012 D-6d, FR-ROUTE-13)
        Long routeId,
        String routeUid,
        String routeCode,
        String routeName,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    /** Build from entity with enriched customer, agent, and optional route fields. */
    public static SalesInvoiceDto from(SalesInvoice inv, String customerName, String agentName,
                                       String routeUid, String routeCode, String routeName) {
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
                inv.getCurrency().value(),
                inv.getCustomerPoNumber(),
                inv.getPaymentTermsId(),
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
                inv.getShipToAddressId(), inv.getBillToAddressId(),
                inv.getShipToAddressText(), inv.getBillToAddressText(),
                inv.getRouteId(),
                routeUid,
                routeCode,
                routeName,
                inv.getVersion(),
                inv.getCreatedAt() != null ? inv.getCreatedAt().toString() : null,
                inv.getCreatedBy(),
                inv.getUpdatedAt() != null ? inv.getUpdatedAt().toString() : null,
                inv.getUpdatedBy()
        );
    }
}
