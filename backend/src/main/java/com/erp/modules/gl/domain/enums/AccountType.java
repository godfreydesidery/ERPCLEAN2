package com.erp.modules.gl.domain.enums;

/**
 * The five account types that drive financial-statement placement and normal balance (BR-GL-12,
 * ADR-0013 D-2a). The type is the authority; the numeric-range convention (1000s→ASSET etc.) is
 * data, not code.
 */
public enum AccountType {
    ASSET,
    LIABILITY,
    EQUITY,
    INCOME,
    EXPENSE
}
