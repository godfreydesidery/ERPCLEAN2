package com.erp.modules.stock.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Informational outbox payload for a stock count post (ADR-0028 D-12).
 * The count GL posts synchronously (direct, not via this event). This payload is
 * published for downstream consumers (e.g. notifications) only.
 */
public record StockCountPostedPayload(
        String countUid,
        Long companyId,
        Long branchId,
        Long locationId,
        String glEntryUid,
        BigDecimal netVarianceValue,
        Instant postedAt
) {}
