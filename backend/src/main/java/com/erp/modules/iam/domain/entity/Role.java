package com.erp.modules.iam.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

/**
 * A named, reusable permission bundle; org-wide (DATA-MODEL §1.6). The {@code role_permission}
 * junction carries no extra columns, so it is mapped as a {@link ManyToMany} rather than its own
 * entity. {@code isSystem} roles are seeded and undeletable (BR-7). Lombok generates the plain
 * accessors (PROJECT-CONVENTIONS §3.7); the constructor and the add/remove behaviour are explicit.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
public class Role extends UidEntity {

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "is_system", nullable = false)
    private boolean system = false;

    /** Scope-typing field (P3). Roles are org-wide by ADR-0001 D-A; NULL = the default org scope. */
    @Column(name = "role_scope", length = 20)
    private String roleScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();

    protected Role() {
        // JPA
    }

    public Role(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public void addPermission(Permission permission) {
        permissions.add(permission);
    }

    public void removePermission(Permission permission) {
        permissions.remove(permission);
    }

    /** Defensive copy — replaces the whole permission set (used when editing a role's permissions). */
    public void setPermissions(Set<Permission> permissions) {
        this.permissions = new LinkedHashSet<>(permissions);
    }
}
