package com.erp.modules.hr.domain.dto;

public record DepartmentDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        boolean active,
        // Org hierarchy + ownership (P2 D6, ADR-0041)
        Long parentDepartmentId,
        Long managerId,
        Long costCentreValueId,
        Long branchId
) {}
