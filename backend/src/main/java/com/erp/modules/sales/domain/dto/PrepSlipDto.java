package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.PrepSlipKind;
import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One slip for one prep station (ADR-0065 D-6). The TILL prints it to
 * {@code printerHost:printerPort} (or its local override); the server never prints. Kitchen slips
 * carry no prices.
 *
 * @param round   the send round the items went out in (1 = the order's first send)
 * @param reprint true when rebuilt on request, so the slip says REPRINT
 * @param byName  who sent (or voided) the items
 * @param lineUids the order lines on the slip — lets a printing device recognise a slip it has
 *                 already printed
 */
public record PrepSlipDto(
        PrepSlipKind kind,
        String stationUid,
        String stationName,
        String printerHost,
        Integer printerPort,
        String orderNumber,
        RestaurantOrderType orderType,
        String tableName,
        Short guestCount,
        int round,
        boolean reprint,
        String byName,
        Instant at,
        List<Line> lines,
        List<String> lineUids
) {
    /**
     * {@code voidReason} is set on VOID slips only. {@code addOn} marks a priced add-on, printed
     * indented under the item before it; {@code course} groups the slip (ADR-0066 D-4, D-5).
     */
    public record Line(String productName, String unitName, BigDecimal quantity, String note,
                       String voidReason, boolean addOn, int course) {}
}
