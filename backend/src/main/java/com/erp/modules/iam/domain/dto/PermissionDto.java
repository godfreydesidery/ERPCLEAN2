package com.erp.modules.iam.domain.dto;

import com.erp.modules.iam.domain.entity.Permission;

/**
 * Response shape for a permission catalogue entry (DATA-MODEL §1.5). Read-only reference data;
 * no uid (Permission is not a UidEntity — addressed by code, not uid).
 */
public record PermissionDto(Long id, String code, String module, String description) {

    public static PermissionDto from(Permission p) {
        return new PermissionDto(p.getId(), p.getCode(), p.getModule(), p.getDescription());
    }
}
