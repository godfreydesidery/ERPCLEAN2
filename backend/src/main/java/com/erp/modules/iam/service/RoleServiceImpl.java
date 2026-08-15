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
import com.erp.platform.security.AuthorityCeiling;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
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
    private final AuthorityCeiling authorityCeiling;

    public RoleServiceImpl(RoleRepository roles,
                           PermissionRepository permissions,
                           PermissionResolver permissionResolver,
                           AuthorityCeiling authorityCeiling) {
        this.roles = roles;
        this.permissions = permissions;
        this.permissionResolver = permissionResolver;
        this.authorityCeiling = authorityCeiling;
    }

    @Override
    public RoleDto create(CreateRoleRequest request) {
        Long organisationId = callerOrganisationId();

        // R-1 (ADR-0062 P4-1d). A tenant may not reuse a code that a GLOBAL role already holds.
        // V102's two partial indexes sit on different partitions, so the database will happily accept
        // (NULL,'CASHIER') alongside (2,'CASHIER') — and that ambiguity is precisely what makes
        // role-code resolution pick a winner rather than an answer. Checked here for a friendly
        // refusal; V102's trigger enforces the same rule against paths that never reach this service.
        if (organisationId != null && roles.existsByOrganisationIdIsNullAndCode(request.code())) {
            throw new ConflictException("Role code already exists: " + request.code());
        }

        // P4-1b. This was a GLOBAL existsByCode, which would have made per-tenant role codes
        // pointless the moment V102 allowed them: tenant B could never use a code tenant A had taken.
        // Scoped to the caller's tenant; a null organisation is the seed/bootstrap path authoring a
        // global role, where global uniqueness is still exactly the right rule.
        boolean taken = organisationId == null
                ? roles.existsByOrganisationIdIsNullAndCode(request.code())
                : roles.existsByOrganisationIdAndCode(organisationId, request.code());
        if (taken) {
            throw new ConflictException("Role code already exists: " + request.code());
        }
        Role role = new Role(request.code(), request.name());
        role.setDescription(request.description());

        // P4-1 (ADR-0062). Without this the role is saved with organisation_id = NULL - and NULL is
        // not "unset", it is the marker for a GLOBAL role. Every role a customer authored would have
        // been published to every tenant through the NULL-tolerant visibility predicate, appearing in
        // strangers' role lists and grantable by them. A tenant's own role belongs to that tenant.
        //
        // A null caller organisation leaves it global, which is correct rather than a fallback: the
        // only paths with no request context are bootstrap and the permission seed, and those are
        // exactly the paths that legitimately author the shipped global roles.
        role.setOrganisationId(organisationId);

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
        // P3-5 (ADR-0062) + invariant I-2: the caller's own roles PLUS the global ones. The
        // NULL-tolerance is not defensive coding - organisation_id IS NULL is what marks the
        // thirteen shipped roles, and a plain equality would hide ORG_ADMIN and every operational
        // bundle from every tenant.
        return roles.findVisibleTo(callerOrganisationId()).stream()
                .map(RoleDto::from)
                .toList();
    }

    @Override
    public RoleDto updateByUid(String uid, UpdateRoleRequest request) {
        Role role = requireByUid(uid);
        // BR-7: system roles are immutable — block name/description edits the same way setPermissions
        // and archiveByUid already do (security audit 2026-06-25).
        if (role.isSystem()) {
            throw new ConflictException("System role cannot be modified: " + role.getCode());
        }
        role.setName(request.name());
        role.setDescription(request.description());
        return RoleDto.from(role); // dirty-checked within the TX
    }

    @Override
    public RoleDto setPermissions(String uid, SetRolePermissionsRequest request) {
        Role role = requireByUid(uid);

        // BR-7: system roles are immutable — the same protection that blocks deletion must also
        // block permission replacement, otherwise an admin can lock themselves out irreversibly.
        if (role.isSystem()) {
            throw new ConflictException("System role permissions cannot be modified: " + role.getCode());
        }

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

        // ADR-0059 authority-containment: a non-root author may only set permissions that are a subset
        // of their own (and may not attach a reserved "power-to-delegate" code unless they are
        // org-admin-tier). Closes the build-your-own-superrole path — minting/widening a role to hold
        // permissions the author does not possess. Root is exempt.
        authorityCeiling.assertCanConfer(RequestContext.get(), requestedCodes);

        role.setPermissions(new LinkedHashSet<>(found));
        // The grants are unchanged but what they confer is not: evict every current holder of this
        // role (and again after commit), rather than clearing the cache for the whole tenant.
        permissionResolver.invalidateRole(role.getId());
        return RoleDto.from(role);
    }

    @Override
    public void archiveByUid(String uid) {
        Role role = requireByUid(uid);
        if (role.isSystem()) {
            throw new ConflictException("System role cannot be archived: " + role.getCode());
        }
        role.setStatus(MasterStatus.ARCHIVED);
        // An archived role resolves to nothing (the resolve query filters on ACTIVE) — every holder
        // loses whatever it conferred, so every holder's cached set must go.
        permissionResolver.invalidateRole(role.getId());
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
        // Another tenant's role is NOT FOUND, not FORBIDDEN: a distinct refusal would confirm the
        // uid exists somewhere, across a tenant boundary.
        return Lookups.orNotFound(roles.findVisibleByUid(uid, callerOrganisationId()), "Role", uid);
    }

    /** The caller's tenant, or null outside a request (bootstrap, async) - see findVisibleTo. */
    private Long callerOrganisationId() {
        RequestContext.Principal p = RequestContext.get();
        return p == null ? null : p.organisationId();
    }

    private Role requireWithPermissions(String uid) {
        // Scoped since P4-1: this used findWithPermissionsByUid, which has no tenant predicate at
        // all, so the role DETAIL read - the one that returns the full permission list - reached past
        // the scoping P3-5 had added to the list and to requireByUid.
        return Lookups.orNotFound(
                roles.findWithPermissionsVisibleByUid(uid, callerOrganisationId()), "Role", uid);
    }
}
