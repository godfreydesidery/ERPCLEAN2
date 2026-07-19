package com.erp.modules.reporting.domain.dto;

/**
 * Shared company header block for printable operational registers (Sales Report, Stock Report —
 * SAM Electronix go-live). Distinct from {@link StatementHeaderDto} (the financial-statement
 * header — companyName/currency/period labels for the P&amp;L/BS/CF family); this one carries the
 * full postal/contact block a printed register needs, shared across modules (sales, stock).
 */
public record ReportCompanyHeaderDto(
        String name,
        String legalName,
        String addressLine1,
        String addressLine2,
        String city,
        String region,
        String country,
        String contactPhone,
        String contactEmail,
        String taxId,
        String vrn) {
}
