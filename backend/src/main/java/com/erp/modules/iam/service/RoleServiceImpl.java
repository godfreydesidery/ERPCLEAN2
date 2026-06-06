package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.CreateRoleRequest;
import com.erp.modules.iam.domain.dto.PermissionDto;
import com.erp.modules.iam.domain.dto.RoleDto;
import com.erp.modules.iam.domain.dto.SetRolePermissionsRequest;
import com.erp.modules.iam.domain.dto.UpdateRoleRequest;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.PermissionResolver;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RoleServiceImpl implements RoleService {

    private final RoleRepository roles;
    private final PermissionRepository permissions;
    private final PermissionResolver permissionResolver;

    public RoleServiceImpl(RoleRepository roles,
                           PermissionRepository permissions,
                           PermissionResolver permissionResolver) {
        this.roles = roles;
        this.permissions = permissions;
        this.permissionResolver = permissionResolver;
    }

    @Override
    public RoleDto create(CreateRoleRequest request) {
        if (roles.existsByCode(request.code())) {
            throw new ConflictException("Role code already exists: " + request.code());
        }
        Role role = new Role(request.code(), request.name());
        role.setDescription(request.description());
        return RoleDto.from(roles.save(role));
    }

    @Override
    @Transactional(readOnly = true)
    public RoleDto getByUid(String uid) {
        return RoleDto.from(requireWithPermissions(uid));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RoleDto> list() {
        // findAllByOrderByName returns lazy permissions; each is fetched on DTO mapping.
        // For a catalogue list we return without permissions loaded — callers use getByUid for detail.
        return roles.findAllByOrderByName().stream()
                .map(RoleDto::from)
                .toList();
    }

    @Override
    public RoleDto updateByUid(String uid, UpdateRoleRequest request) {
        Role role = requireByUid(uid);
        role.setName(request.name());
        role.setDescription(request.description());
        return RoleDto.from(role); // dirty-checked within the TX
    }

    @Override
    public RoleDto setPermissions(String uid, SetRolePermissionsRequest request) {
        Role role = requireByUid(uid);

        List<String> requestedCodes = request.permissionCodes();
        List<Permission> found = permissions.findByCodeIn(requestedCodes);

        if (found.size() != requestedCodes.size()) {
            Set<String> foundCodes = found.stream()
                    .map(Permission::getCode)
                    .collect(Collectors.toSet());
            List<String> unknown = requestedCodes.stream()
                    .filter(c -> !foundCodes.contains(c))
                    .sorted()
                    .toList();
            throw new ConflictException("Unknown permission codes: " + unknown);
        }

        role.setPermissions(new LinkedHashSet<>(found));
        permissionResolver.invalidate();
        return RoleDto.from(role);
    }

    @Override
    public void archiveByUid(String uid) {
        Role role = requireByUid(uid);
        if (role.isSystem()) {
            throw new ConflictException("System role cannot be archived: " + role.getCode());
        }
        role.setStatus(MasterStatus.ARCHIVED);
        permissionResolver.invalidate();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PermissionDto> listPermissions() {
        return permissions.findAll().stream()
                .map(PermissionDto::from)
                .toList();
    }

    // --- private helpers ---

    private Role requireByUid(String uid) {
        return Lookups.orNotFound(roles.findByUid(uid), "Role", uid);
    }

    private Role requireWithPermissions(String uid) {
        return Lookups.orNotFound(roles.findWithPermissionsByUid(uid), "Role", uid);
    }
}
