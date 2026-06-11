package com.erp.modules.documents.domain.entity;

import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.modules.documents.domain.enums.RendererKey;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-company registry row per renderable document type (ADR-0023 D-3).
 * One row per (company_id, document_type) — uq_document_template_company_type.
 * Status ACTIVE/INACTIVE controls whether the type can be rendered (FR-DOC-03 / BR-DOC-04).
 */
@Getter
@Setter
@Entity
@Table(name = "document_templates")
public class DocumentTemplate extends UidEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "renderer_key", nullable = false, length = 40)
    private RendererKey rendererKey;

    /** FK → document_branding(id); NULL = use the company default profile. */
    @Column(name = "branding_id")
    private Long brandingId;

    @Column(name = "title", nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "status_changed_at")
    private Instant statusChangedAt;

    @Column(name = "status_changed_by")
    private Long statusChangedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected DocumentTemplate() {
    }

    public DocumentTemplate(Long companyId, DocumentType documentType,
                             RendererKey rendererKey, String title) {
        this.companyId    = companyId;
        this.documentType = documentType;
        this.rendererKey  = rendererKey;
        this.title        = title;
    }
}
