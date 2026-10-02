package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;

/**
 * One supplier (in one currency) on the Purchases by Supplier report.
 *
 * <p>All values exclude VAT except {@code unpaidAmount}, which is what is still owed on the bills
 * and so includes it. {@code returnsValue} / {@code netPurchases} are null when the caller may not
 * see purchase returns, and {@code billedAmount} / {@code unpaidAmount} are null when the caller may
 * not see supplier bills — null means "not shown to you", never zero.
 *
 * @param receipts      goods receipts received from this supplier in the period
 * @param receivedValue value received, less receipts voided in the period
 * @param returnsValue  value of goods returned to the supplier (confirmed returns, at receipt cost)
 * @param netPurchases  receivedValue - returnsValue
 * @param billedAmount  supplier bills dated in the period, excluding VAT
 * @param unpaidAmount  what is still unpaid TODAY on those same bills, including VAT
 */
public record PurchasesBySupplierRowDto(
        String     supplierCode,
        String     supplierName,
        String     currency,
        long       receipts,
        BigDecimal receivedValue,
        BigDecimal returnsValue,
        BigDecimal netPurchases,
        BigDecimal billedAmount,
        BigDecimal unpaidAmount
) {}
