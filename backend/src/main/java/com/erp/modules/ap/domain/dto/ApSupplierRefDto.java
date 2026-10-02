package com.erp.modules.ap.domain.dto;

/** A supplier resolved inside one company — the id the AP reads take, plus what a statement prints. */
public record ApSupplierRefDto(
        Long id,
        String uid,
        String code,
        String name,
        String tin,
        String vrn
) {}
