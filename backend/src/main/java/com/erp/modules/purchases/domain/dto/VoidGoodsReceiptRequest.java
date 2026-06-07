package com.erp.modules.purchases.domain.dto;

/**
 * Request DTO to void a RECEIVED Goods Receipt (ADR-0011 D-6, FR-PURCH-09).
 */
public record VoidGoodsReceiptRequest(String reason) {}
