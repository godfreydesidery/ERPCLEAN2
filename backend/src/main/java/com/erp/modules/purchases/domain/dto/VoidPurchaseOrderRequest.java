package com.erp.modules.purchases.domain.dto;

/**
 * Request DTO to void a Purchase Order (ADR-0011 D-6, FR-PURCH-09).
 */
public record VoidPurchaseOrderRequest(String reason) {}
