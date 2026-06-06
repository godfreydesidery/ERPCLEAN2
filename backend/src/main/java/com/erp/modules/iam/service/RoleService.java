package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateRoleRequest;
import com.erp.modules.iam.domain.dto.PermissionDto;
import com.erp.modules.iam.domain.dto.RoleDto;
import com.erp.modules.iam.domain.dto.SetRolePermissionsRequest;
import com.erp.modules.iam.domain.dto.UpdateRoleRequest;
import java.util.List;

/**
 * Role catalogue administration (DATA-MODEL §1.6). Roles are org-wide; company scope is not
 * required at the catalogue level (ADR-0002 D-3). Controllers depend on this interface, not the
 * impl (DIP).
 */
public interface RoleService {

    RoleDto create(CreateRoleRequest request);

    RoleDto getByUid(String uid);

    List<RoleDto> list();

    RoleDto updateByUid(String uid, UpdateRoleRequest request);

    RoleDto setPermissions(String uid, SetRolePermissionsRequest request);

    void archiveByUid(String uid);

    List<PermissionDto> listPermissions();
}
