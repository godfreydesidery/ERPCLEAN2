package com.erp.modules.sales.domain.dto;

import java.util.List;

/**
 * The modifier groups one menu item offers, in order — what the till asks when the item is tapped
 * (ADR-0066 D-4). Only items with at least one active group are listed.
 */
public record ModifierLinkDto(Long productId, String productUid, List<String> groupUids) {}
