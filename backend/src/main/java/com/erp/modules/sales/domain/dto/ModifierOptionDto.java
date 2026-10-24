package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;

/**
 * One option of a modifier group. With a product it is a priced add-on (a child line on the
 * order); without one it is an instruction that goes into the item's note.
 */
public record ModifierOptionDto(
        String uid,
        String label,
        Long productId,
        String productUid,
        String productName,
        Integer sortOrder,
        MasterStatus status
) {}
