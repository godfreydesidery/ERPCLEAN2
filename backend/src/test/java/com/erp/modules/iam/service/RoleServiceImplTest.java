package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.RoleDto;
import com.erp.modules.iam.domain.dto.UpdateRoleRequest;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.AuthorityCeiling;
import com.erp.platform.security.PermissionResolver;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the BR-7 immutability guard added to {@link RoleServiceImpl#updateByUid}.
 *
 * <p>BR-7: system roles must not be renamed/described via the API. The guard already existed for
 * {@code setPermissions} and {@code archiveByUid}; this test verifies it is now enforced on
 * {@code updateByUid} as well (security audit 2026-06-25).
 */
class RoleServiceImplTest {

    private static final String ROLE_UID = "uid-role-001";

    private RoleRepository  roleRepo;
    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        roleRepo = mock(RoleRepository.class);
        service  = new RoleServiceImpl(
                roleRepo,
                mock(PermissionRepository.class),
                mock(PermissionResolver.class),
                mock(AuthorityCeiling.class));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Builds a minimal Role mock. For non-system roles also stubs getStatus() and getPermissions()
     * so RoleDto.from() can serialise the return value without NPE.
     */
    private Role stubRole(boolean isSystem) {
        Role role = mock(Role.class);
        when(role.getUid()).thenReturn(ROLE_UID);
        when(role.isSystem()).thenReturn(isSystem);
        when(role.getCode()).thenReturn(isSystem ? "ORG_ADMIN" : "CUSTOM_ROLE");
        if (!isSystem) {
            when(role.getName()).thenReturn("Custom Role");
            when(role.getStatus()).thenReturn(MasterStatus.ACTIVE);
            when(role.getPermissions()).thenReturn(Set.of());
        }
        when(roleRepo.findByUid(ROLE_UID)).thenReturn(Optional.of(role));
        return role;
    }

    // -----------------------------------------------------------------------
    // BR-7: updateByUid — system role is immutable
    // -----------------------------------------------------------------------

    @Test
    void updateByUid_systemRole_throwsConflict() {
        stubRole(true);
        UpdateRoleRequest req = new UpdateRoleRequest("New Name", "New desc");

        assertThatThrownBy(() -> service.updateByUid(ROLE_UID, req))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("System role cannot be modified");
    }

    @Test
    void updateByUid_nonSystemRole_succeeds() {
        stubRole(false);
        UpdateRoleRequest req = new UpdateRoleRequest("Updated Name", "Updated desc");

        RoleDto result = service.updateByUid(ROLE_UID, req);

        assertThat(result).isNotNull();
        assertThat(result.uid()).isEqualTo(ROLE_UID);
    }
}
