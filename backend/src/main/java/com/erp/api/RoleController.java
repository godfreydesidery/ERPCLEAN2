package com.erp.api;

import com.erp.modules.iam.domain.dto.CreateRoleRequest;
import com.erp.modules.iam.domain.dto.PermissionDto;
import com.erp.modules.iam.domain.dto.RoleDto;
import com.erp.modules.iam.domain.dto.ScreenReadGapDto;
import com.erp.modules.iam.domain.dto.SetRolePermissionsRequest;
import com.erp.modules.iam.domain.dto.UpdateRoleRequest;
import com.erp.modules.iam.service.RoleReadClosureService;
import com.erp.modules.iam.service.RoleService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Role catalogue CRUD (DATA-MODEL §1.6, ADR-0002). Roles are org-wide; {@code @perm.has} is used
 * throughout (no company-scope check needed here). Returns raw DTOs — {@code ApiResponseAdvice}
 * wraps them in the envelope. Uses {@code @perm.has} (ADR-0002 Bug-1 fix).
 */
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private final RoleService roleService;
    private final RoleReadClosureService readClosureService;

    public RoleController(RoleService roleService, RoleReadClosureService readClosureService) {
        this.roleService = roleService;
        this.readClosureService = readClosureService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('ROLE.VIEW')")
    public List<RoleDto> list() {
        return roleService.list();
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('ROLE.VIEW')")
    public RoleDto get(@PathVariable String uid) {
        return roleService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('ROLE.ADMIN')")
    public RoleDto create(@Valid @RequestBody CreateRoleRequest request) {
        return roleService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('ROLE.ADMIN')")
    public RoleDto update(@PathVariable String uid,
                          @Valid @RequestBody UpdateRoleRequest request) {
        return roleService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/permissions")
    @PreAuthorize("@perm.has('ROLE.ADMIN')")
    public RoleDto setPermissions(@PathVariable String uid,
                                  @Valid @RequestBody SetRolePermissionsRequest request) {
        return roleService.setPermissions(uid, request);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('ROLE.ADMIN')")
    public void archive(@PathVariable String uid) {
        roleService.archiveByUid(uid);
    }

    @GetMapping("/permissions")
    @PreAuthorize("@perm.has('PERMISSION.VIEW')")
    public List<PermissionDto> listPermissions() {
        return roleService.listPermissions();
    }

    /**
     * Advisory read-closure gap report for this role (ADR-0047 Grant-time validator).
     * Returns the screens the role can open but whose required strict reads the role lacks.
     * Empty list = fully closed. NEVER blocks a grant — advisory only.
     */
    @GetMapping("/uid/{uid}/read-closure-gaps")
    @PreAuthorize("@perm.has('ROLE.VIEW')")
    public List<ScreenReadGapDto> readClosureGaps(@PathVariable String uid) {
        return readClosureService.gapsForRole(uid);
    }
}
