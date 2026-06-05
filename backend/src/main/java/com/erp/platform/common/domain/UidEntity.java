package com.erp.platform.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Version;

/**
 * Base class for every externally exposed entity (PROJECT-CONVENTIONS §3.3, ADR-0001 D-G):
 * a numeric {@code id} for internal/body joins and a stable {@code uid} (ULID) for URLs and
 * cross-system references. The {@code uid} is assigned on persist if not already set.
 *
 * <p>Entities do NOT use Lombok (PROJECT-CONVENTIONS §3.7) — explicit accessors here.
 */
@MappedSuperclass
public abstract class UidEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, updatable = false, length = 26)
    private String uid;

    /** Optimistic-lock guard on mutable aggregates (PROJECT-CONVENTIONS §3.6). */
    @Version
    private Long version;

    @PrePersist
    void assignUid() {
        if (uid == null) {
            uid = Ulid.next();
        }
    }

    public Long getId() {
        return id;
    }

    public String getUid() {
        return uid;
    }

    /** For tests that must set a uid before persist (set via reflection in unit tests). */
    protected void setUid(String uid) {
        this.uid = uid;
    }

    public Long getVersion() {
        return version;
    }
}
