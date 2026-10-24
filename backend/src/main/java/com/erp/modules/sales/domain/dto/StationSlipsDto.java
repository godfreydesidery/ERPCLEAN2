package com.erp.modules.sales.domain.dto;

import java.time.Instant;
import java.util.List;

/**
 * Slips for one station since a point in time, for a till that prints the station's paper itself
 * — a kitchen PC with a USB printer (ADR-0066 D-6). Ask again with {@code since = until}; a small
 * overlap is safe, as every slip carries its {@code lineUids} for the device to skip repeats.
 */
public record StationSlipsDto(Instant until, List<PrepSlipDto> slips) {}
