package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;

/**
 * A prep station and the network printer its slips go to (ADR-0065 D-4). {@code defaultStation}
 * is the branch's catch-all for items whose category is not routed.
 */
public record PrepStationDto(
        Long id,
        String uid,
        Long branchId,
        String name,
        String printerHost,
        Integer printerPort,
        boolean defaultStation,
        MasterStatus status
) {}
