package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.dto.PermissionDto;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.security.AuthorityCeiling;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The role editor's permission catalogue hides the vendor's {@code platform} module from tenants
 * (P4-1e follow-up, MULTITENANCY-PLAN §14 loose ends). Presentation only — grant-time enforcement is
 * {@code AuthorityCeiling}'s and is not exercised here.
 */
class RolePermissionCatalogueTest {

    private RoleServiceImpl service;

    @BeforeEach
    void setUp() {
        PermissionRepository permissions = mock(PermissionRepository.class);
        // Built before the outer stub: nesting when() inside thenReturn() is unfinished stubbing.
        List<Permission> catalogue = List.of(
                permission(1L, "SALES_INVOICE.POST", "sales"),
                permission(2L, "ORG.CREATE", "platform"),
                permission(3L, "USER.MANAGE", "iam"));
        when(permissions.findAll()).thenReturn(catalogue);
        service = new RoleServiceImpl(mock(RoleRepository.class), permissions,
                mock(PermissionResolver.class), mock(AuthorityCeiling.class));
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void aTenantAdministratorDoesNotSeeThePlatformModule() {
        actingAs(false);
        assertThat(service.listPermissions()).extracting(PermissionDto::code)
                .containsExactly("SALES_INVOICE.POST", "USER.MANAGE");
    }

    @Test
    void thePlatformOperatorSeesEverything() {
        actingAs(true);
        assertThat(service.listPermissions()).extracting(PermissionDto::code)
                .containsExactly("SALES_INVOICE.POST", "ORG.CREATE", "USER.MANAGE");
    }

    @Test
    void outsideARequestThePlatformModuleIsHidden() {
        assertThat(service.listPermissions()).extracting(PermissionDto::module)
                .doesNotContain("platform");
    }

    private static void actingAs(boolean root) {
        RequestContext.set(new RequestContext.Principal(
                1L, "caller", root, 10L, 20L, "127.0.0.1", 1L));
    }

    private static Permission permission(Long id, String code, String module) {
        Permission p = mock(Permission.class);
        when(p.getId()).thenReturn(id);
        when(p.getCode()).thenReturn(code);
        when(p.getModule()).thenReturn(module);
        return p;
    }
}
