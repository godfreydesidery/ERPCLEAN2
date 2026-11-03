package com.erp.modules.sales.domain.dto;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import java.math.BigDecimal;
import java.util.List;

/**
 * How the period's sales were paid, and what did not end in a sale: refunds, voided receipts,
 * cancelled orders and voided items. Completion counts the orders OPENED in the range by how
 * they stand now.
 */
public record RestaurantPaymentsReportDto(
        ReportCompanyHeaderDto company,
        String fromDate,
        String toDate,
        String branchName,
        String currency,
        List<Tender> tenders,
        BigDecimal totalPaid,
        Completion completion,
        List<Refund> refunds,
        BigDecimal refundTotal,
        List<VoidedReceipt> voidedReceipts,
        BigDecimal voidedReceiptTotal,
        List<CancelledOrder> cancelledOrders,
        List<VoidedItem> voidedItems,
        String generatedAt) {

    /** {@code share} is the percentage of {@code totalPaid}. */
    public record Tender(String tenderType, long payments, BigDecimal amount, BigDecimal share) {}

    /** {@code completionRate} = paid / opened, as a percentage; null when nothing was opened. */
    public record Completion(long opened, long paid, long cancelled, long pending,
                             BigDecimal completionRate) {}

    public record Refund(String at, String receiptNumber, String reason, String by,
                         BigDecimal amount) {}

    public record VoidedReceipt(String soldAt, String receiptNumber, String by,
                                BigDecimal amount) {}

    public record CancelledOrder(String orderNumber, String servedAt, String waiterName,
                                 String openedAt, String cancelledAt, String reason,
                                 BigDecimal items) {}

    public record VoidedItem(String voidedAt, String orderNumber, String servedAt,
                             String waiterName, String productName, BigDecimal quantity,
                             String reason) {}
}
