package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.dto.UserRoleDto;
import java.util.List;

/**
 * Role-assignment lifecycle: grant, revoke, and list (DATA-MODEL §1.8). Scope is enforced
 * per-grant via {@code ScopeGuard} (ADR-0002 D-3). Controllers depend on this interface, not the
 * impl (DIP).
 */
public interface UserRoleService {

    UserRoleDto grant(GrantRoleRequest request);

    void revoke(String userRoleUid);

    List<UserRoleDto> listForUser(String userUid);
}
