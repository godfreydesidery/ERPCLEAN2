package com.erp.modules.hr.domain.dto;

public record DepartmentDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        boolean active
) {}
