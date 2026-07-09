package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.dto.CreateRoleRequest;
import com.erp.modules.iam.domain.dto.PermissionDto;
import com.erp.modules.iam.domain.dto.RoleDto;
import com.erp.modules.iam.domain.dto.SetRolePermissionsRequest;
import com.erp.modules.iam.domain.dto.UpdateRoleRequest;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Integration tests for {@link RoleServiceImpl} against real Postgres. Covers: duplicate-code
 * rejection; setPermissions with unknown codes (ConflictException) and with valid codes (dto
 * reflects them); archiveByUid on a system role (ConflictException) and on a normal role (ARCHIVED);
 * list/getByUid round-trip.
 */
class RoleServiceImplIT extends PostgresIntegrationTest {

    @Autowired private RoleService roleService;
    @Autowired private RoleRepository roleRepo;
    @Autowired private IamTestData testData;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        // ORG_ADMIN (is_system=true) was seeded by V1 migration and is preserved by clearAll().
        // A root principal so the role-mechanics tests are exempt from the ADR-0059 authority ceiling
        // (only admins edit roles); individual authorization tests override this with a non-root one.
        RequestContext.set(new RequestContext.Principal(0L, "root", true, null, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ---------------------------------------------------------------
    // create: duplicate code -> ConflictException
    // ---------------------------------------------------------------

    @Test
    void create_duplicateCode_throwsConflict() {
        roleService.create(new CreateRoleRequest("ANALYST", "Analyst", null));
        assertThatThrownBy(() -> roleService.create(new CreateRoleRequest("ANALYST", "Another", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ANALYST");
    }

    // ---------------------------------------------------------------
    // create then getByUid: round-trip
    // ---------------------------------------------------------------

    @Test
    void create_thenGetByUid_roundTrips() {
        // Use a non-seeded code: CASHIER is now a shipped is_system bundle (ADR-0057) that survives
        // clearAll() (which deletes only is_system=false roles), so it would collide on create.
        RoleDto created = roleService.create(new CreateRoleRequest("RT_ROLE", "Round Trip", "Handles cash"));

        assertThat(created.uid()).isNotBlank();
        assertThat(created.code()).isEqualTo("RT_ROLE");
        assertThat(created.name()).isEqualTo("Round Trip");
        assertThat(created.description()).isEqualTo("Handles cash");
        assertThat(created.system()).isFalse();
        assertThat(created.status()).isEqualTo("ACTIVE");

        RoleDto fetched = roleService.getByUid(created.uid());
        assertThat(fetched.uid()).isEqualTo(created.uid());
        assertThat(fetched.code()).isEqualTo("RT_ROLE");
    }

    @Test
    void getByUid_unknownUid_throwsNotFound() {
        assertThatThrownBy(() -> roleService.getByUid("01HZZZZZZZZZZZZZZZZZZZZZZY"))
                .isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------
    // list: includes freshly created role
    // ---------------------------------------------------------------

    @Test
    void list_includesCreatedRoles_orderedByName() {
        roleService.create(new CreateRoleRequest("ZEBRA", "Zebra Role", null));
        roleService.create(new CreateRoleRequest("APPLE", "Apple Role", null));

        List<RoleDto> all = roleService.list();
        List<String> names = all.stream().map(RoleDto::name).toList();

        // Seeded ORG_ADMIN is "Organisation Administrator" — all three coexist.
        assertThat(names).contains("Apple Role", "Zebra Role");
        // Alphabetical: Apple < Organisation < Zebra
        int appleIdx = names.indexOf("Apple Role");
        int zebraIdx = names.indexOf("Zebra Role");
        assertThat(appleIdx).isLessThan(zebraIdx);
    }

    // ---------------------------------------------------------------
    // updateByUid: mutable fields change
    // ---------------------------------------------------------------

    @Test
    void updateByUid_changesMutableFields() {
        RoleDto created = roleService.create(new CreateRoleRequest("MGR", "Manager", "Old desc"));
        RoleDto updated = roleService.updateByUid(created.uid(), new UpdateRoleRequest("Senior Manager", "New desc"));

        assertThat(updated.name()).isEqualTo("Senior Manager");
        assertThat(updated.description()).isEqualTo("New desc");
        assertThat(updated.code()).isEqualTo("MGR"); // code is immutable
    }

    // ---------------------------------------------------------------
    // setPermissions: unknown code -> ConflictException
    // ---------------------------------------------------------------

    @Test
    void setPermissions_unknownCode_throwsConflict() {
        RoleDto role = roleService.create(new CreateRoleRequest("TESTER", "Tester", null));
        assertThatThrownBy(() -> roleService.setPermissions(
                role.uid(), new SetRolePermissionsRequest(List.of("DOES.NOT.EXIST"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("DOES.NOT.EXIST");
    }

    // ---------------------------------------------------------------
    // setPermissions: valid codes -> RoleDto.permissionCodes reflects them
    // ---------------------------------------------------------------

    @Test
    void setPermissions_validCodes_arePresentInDto() {
        RoleDto role = roleService.create(new CreateRoleRequest("VIEWER", "Viewer", null));
        RoleDto updated = roleService.setPermissions(role.uid(),
                new SetRolePermissionsRequest(List.of("ROLE.VIEW", "USER.VIEW")));

        assertThat(updated.permissionCodes())
                .as("both seeded permission codes must be in the dto")
                .containsExactlyInAnyOrder("ROLE.VIEW", "USER.VIEW");
    }

    @Test
    void setPermissions_replacesExistingPermissions() {
        RoleDto role = roleService.create(new CreateRoleRequest("SWAPPER", "Swapper", null));
        roleService.setPermissions(role.uid(), new SetRolePermissionsRequest(List.of("ROLE.VIEW")));

        // Replace with a different set
        RoleDto replaced = roleService.setPermissions(role.uid(),
                new SetRolePermissionsRequest(List.of("USER.VIEW")));

        assertThat(replaced.permissionCodes()).containsExactly("USER.VIEW");
        assertThat(replaced.permissionCodes()).doesNotContain("ROLE.VIEW");
    }

    @Test
    void setPermissions_emptyList_clearsAllPermissions() {
        RoleDto role = roleService.create(new CreateRoleRequest("EMPTY", "Empty Role", null));
        roleService.setPermissions(role.uid(), new SetRolePermissionsRequest(List.of("ROLE.VIEW")));

        RoleDto cleared = roleService.setPermissions(role.uid(), new SetRolePermissionsRequest(List.of()));
        assertThat(cleared.permissionCodes()).isEmpty();
    }

    // ---------------------------------------------------------------
    // ADR-0059 authority ceiling: a non-root author may only set permissions
    // that are a subset of their own — build-your-own-superrole is refused.
    // ---------------------------------------------------------------

    @Test
    void setPermissions_nonRootWithoutThePermission_throwsForbidden() {
        RoleDto role = roleService.create(new CreateRoleRequest("LIMITED", "Limited", null));
        // A non-root caller with no resolved permissions (empty effective set) cannot attach USER.VIEW.
        String roleUid = role.uid();
        SetRolePermissionsRequest req = new SetRolePermissionsRequest(List.of("USER.VIEW"));
        RequestContext.set(new RequestContext.Principal(999L, "limited", false, 888L, null, null));

        assertThatThrownBy(() -> roleService.setPermissions(roleUid, req))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---------------------------------------------------------------
    // archiveByUid: system role -> ConflictException; normal role -> ARCHIVED
    // ---------------------------------------------------------------

    @Test
    void archiveByUid_systemRole_throwsConflict() {
        // ORG_ADMIN is seeded as is_system=true by V1 migration
        String orgAdminUid = roleRepo.findByCode("ORG_ADMIN")
                .orElseThrow(() -> new IllegalStateException("ORG_ADMIN must be seeded"))
                .getUid();

        assertThatThrownBy(() -> roleService.archiveByUid(orgAdminUid))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ORG_ADMIN");
    }

    // ---------------------------------------------------------------
    // setPermissions: system role -> ConflictException (issue #4 fix)
    // ---------------------------------------------------------------

    @Test
    void setPermissions_systemRole_throwsConflict() {
        // ORG_ADMIN is seeded as is_system=true; stripping its permissions must be refused.
        // UID resolved outside the lambda so assertThatThrownBy has exactly one throwable site (S5778).
        String orgAdminUid = orgAdminUid();

        assertThatThrownBy(() -> roleService.setPermissions(
                orgAdminUid, new SetRolePermissionsRequest(List.of("USER.VIEW"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ORG_ADMIN");
    }

    @Test
    void setPermissions_systemRole_emptyList_throwsConflict() {
        // Emptying a system role's permission set must also be refused.
        String orgAdminUid = orgAdminUid();

        assertThatThrownBy(() -> roleService.setPermissions(
                orgAdminUid, new SetRolePermissionsRequest(List.of())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ORG_ADMIN");
    }

    @Test
    void archiveByUid_normalRole_setsStatusArchived() {
        RoleDto role = roleService.create(new CreateRoleRequest("ARCHIVEME", "Archive Me", null));
        roleService.archiveByUid(role.uid());

        // Verify via repo — the service doesn't return the updated role from archiveByUid
        var entity = roleRepo.findByUid(role.uid()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(MasterStatus.ARCHIVED);
    }

    // ---------------------------------------------------------------
    // listPermissions: seeded catalogue is present
    // ---------------------------------------------------------------

    @Test
    void listPermissions_returnsSeededCatalogue() {
        List<PermissionDto> perms = roleService.listPermissions();
        List<String> codes = perms.stream().map(PermissionDto::code).toList();

        assertThat(codes).contains(
                "COMPANY.MANAGE", "BRANCH.MANAGE", "USER.MANAGE",
                "ROLE.MANAGE", "PERMISSION.VIEW", "AUDIT.VIEW");
    }

    // ---------------------------------------------------------------
    // private helpers
    // ---------------------------------------------------------------

    private String orgAdminUid() {
        return roleRepo.findByCode("ORG_ADMIN")
                .orElseThrow(() -> new IllegalStateException("ORG_ADMIN must be seeded"))
                .getUid();
    }
}
