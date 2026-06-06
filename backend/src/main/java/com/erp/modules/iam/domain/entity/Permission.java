package com.erp.modules.iam.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An atomic access unit (DATA-MODEL §1.5). Org-wide reference data, seeded via Flyway and addressed
 * by {@code code} (dot-separated, e.g. {@code USER.MANAGE}). Not a
 * {@link com.erp.platform.common.domain.UidEntity}: it has no external uid (referenced by code) and
 * is never created through the API (BR-7 — the seeded set is undeletable). Lombok generates the
 * plain accessors (PROJECT-CONVENTIONS §3.7); no {@code @Data} on entities.
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code", nullable = false, length = 80)
    private String code;

    @Column(name = "module", nullable = false, length = 40)
    private String module;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    protected Permission() {
        // JPA
    }
}
