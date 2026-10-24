package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.KitchenTicketDto;
import java.util.List;

/**
 * The kitchen display (ADR-0066 D-6): a station's open tickets, and bumping items ready.
 */
public interface RestaurantKitchenService {

    /** The station's tickets not yet bumped, oldest first. */
    List<KitchenTicketDto> tickets(String stationUid);

    /** Marks the items (and their add-ons) ready. Already-ready items are left as they are. */
    void ready(List<String> lineUids);

    /** Undoes a bump made by mistake. */
    void recall(List<String> lineUids);
}
