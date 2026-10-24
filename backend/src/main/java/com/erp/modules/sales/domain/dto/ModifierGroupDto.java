package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import java.util.List;

/**
 * A modifier group with its options and the menu items it is offered on (ADR-0066 D-4).
 * {@code maxSelect} null = no upper limit.
 */
public record ModifierGroupDto(
        String uid,
        String name,
        Short minSelect,
        Short maxSelect,
        Integer sortOrder,
        MasterStatus status,
        List<ModifierOptionDto> options,
        List<ProductRefDto> products
) {}
