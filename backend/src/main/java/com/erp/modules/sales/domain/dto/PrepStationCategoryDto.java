package com.erp.modules.sales.domain.dto;

/** One routing row: items in {@code category} print at {@code prepStation*} (ADR-0065 D-4). */
public record PrepStationCategoryDto(
        String uid,
        String category,
        String prepStationUid,
        String prepStationName
) {}
