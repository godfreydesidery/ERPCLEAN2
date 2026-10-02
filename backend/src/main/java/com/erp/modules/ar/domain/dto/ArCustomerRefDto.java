package com.erp.modules.ar.domain.dto;

/** A customer resolved inside one company — the id the AR reads take, plus what a statement prints. */
public record ArCustomerRefDto(
        Long id,
        String uid,
        String code,
        String name,
        String tin,
        String vrn
) {}
