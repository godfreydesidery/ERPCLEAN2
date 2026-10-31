package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A restaurant order with its items (ADR-0065 D-5). Carries no prices: the bill
 * ({@link RestaurantBillDto}) is the priced view, computed by the server.
 *
 * <p>{@code pendingLineCount} is how many items have not been sent to a prep station yet — the
 * till enables Send on it and asks "send first?" before billing.
 */
public record RestaurantOrderDto(
        Long id,
        String uid,
        Long branchId,
        String orderNumber,
        RestaurantOrderType orderType,
        RestaurantOrderStatus status,
        String tableUid,
        String tableName,
        Short guestCount,
        Long customerId,
        Long openedBy,
        String openedByName,
        Instant openedAt,
        Instant billedAt,
        BigDecimal billedTotalAmount,
        Integer billPrintCount,
        Instant paidAt,
        String salesInvoiceUid,
        Instant cancelledAt,
        String cancelReason,
        String notes,
        int pendingLineCount,
        List<RestaurantOrderLineDto> lines,
        /** The waiter holding the order now (ADR-0067): the last transfer's, else the opener. */
        String waiterUid,
        String waiterName,
        /** When it was transferred to them; null while the opener still holds it. */
        Instant waiterSince
) {}
