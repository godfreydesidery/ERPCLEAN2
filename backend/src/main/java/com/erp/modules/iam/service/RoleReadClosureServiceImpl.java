package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.RoleDto;
import com.erp.modules.iam.domain.dto.ScreenReadGapDto;
import com.erp.platform.security.ScreenReadClosureManifest;
import com.erp.platform.security.ScreenReadClosureManifest.ReadEntry;
import com.erp.platform.security.ScreenReadClosureManifest.Screen;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Advisory read-closure validator (ADR-0047 Grant-time validator section).
 *
 * <p>Pure function over {@code (role grants, manifest)} — no security principal, no
 * {@code PermissionChecks}, no cross-module entity import. It delegates the role lookup to
 * {@link RoleService#getByUid} (same module, same layer) and the manifest read to
 * {@link ScreenReadClosureManifest} (platform, injected — no cross-module entity import).
 *
 * <p>Gate-form classification mirrors {@code RolePermissionClosureTest}'s check (b):
 * only {@code has} and {@code scoped} are closure-bearing. {@code hasOrMember},
 * {@code scopedOrMember}, and {@code disjunction} reads are satisfied by membership or the
 * adjacent verb and are therefore excluded from the gap computation.
 */
@Service
@Transactional(readOnly = true)
public class RoleReadClosureServiceImpl implements RoleReadClosureService {

    /**
     * Gate forms that require the role to explicitly carry the permission code.
     * Member-floor ({@code hasOrMember}/{@code scopedOrMember}) and disjunction reads are excluded
     * — they are satisfied by company membership or the adjacent verb respectively.
     */
    static final Set<String> CLOSURE_BEARING_GATES = Set.of("has", "scoped");

    private final RoleService roleService;
    private final ScreenReadClosureManifest manifest;

    public RoleReadClosureServiceImpl(RoleService roleService,
                                      ScreenReadClosureManifest manifest) {
        this.roleService = roleService;
        this.manifest = manifest;
    }

    @Override
    public List<ScreenReadGapDto> gapsForRole(String roleUid) {
        RoleDto role = roleService.getByUid(roleUid);
        Set<String> grants = Set.copyOf(role.permissionCodes());
        return computeGaps(grants, manifest.screens());
    }

    /**
     * Pure gap computation over a grant set and a list of manifest screens.
     * Package-visible for unit-testing without Spring context.
     */
    static List<ScreenReadGapDto> computeGaps(Set<String> grants, List<Screen> screens) {
        List<ScreenReadGapDto> gaps = new ArrayList<>();
        for (Screen screen : screens) {
            // Only analyse screens the role can open.
            if (!grants.contains(screen.accessPermission())) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (ReadEntry read : screen.reads()) {
                if ("required".equals(read.necessity())
                        && CLOSURE_BEARING_GATES.contains(read.gate())
                        && !grants.contains(read.code())) {
                    missing.add(read.code());
                }
            }
            if (!missing.isEmpty()) {
                gaps.add(new ScreenReadGapDto(
                        screen.screen(),
                        screen.accessPermission(),
                        List.copyOf(missing)));
            }
        }
        return List.copyOf(gaps);
    }
}
