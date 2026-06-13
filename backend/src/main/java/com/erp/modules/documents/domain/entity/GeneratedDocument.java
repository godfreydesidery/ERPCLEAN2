package com.erp.modules.documents.domain.entity;

import com.erp.modules.documents.domain.enums.DocumentType;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only render log — one row per committed render (ADR-0023 D-4 / BR-DOC-08).
 * Never updated or deleted. Addressed by uid (/api/documents/{uid}/download).
 * Bytes are NOT persisted — re-rendered on download (OQ-DOC-03).
 */
@Getter
@Entity
@Table(name = "generated_documents")
public class GeneratedDocument extends UidEntity {

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "document_number", nullable = false, length = 30)
    private String documentNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private DocumentType documentType;

    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType;

    /** Nullable: a parameterised render (e.g. AR_STATEMENT) has no single source uid. */
    @Column(name = "source_uid", length = 26)
    private String sourceUid;

    /** JSONB — parameters for a parameterised render (e.g. {customerUid, fromDate, toDate}). */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_params", columnDefinition = "JSONB")
    private String sourceParams;

    @Column(name = "branding_id")
    private Long brandingId;

    /** SHA-256 hex of the rendered bytes at first render — divergence detection on re-download. */
    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "byte_size")
    private Integer byteSize;

    @Column(name = "mime_type", nullable = false, length = 80)
    private String mimeType = "application/pdf";

    @Column(name = "generated_by", nullable = false)
    private Long generatedBy;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected GeneratedDocument() {
    }

    public GeneratedDocument(Long companyId, Long branchId, String documentNumber,
                              DocumentType documentType, String sourceType,
                              String sourceUid, String sourceParams,
                              Long brandingId, Long generatedBy) {
        this.companyId      = companyId;
        this.branchId       = branchId;
        this.documentNumber = documentNumber;
        this.documentType   = documentType;
        this.sourceType     = sourceType;
        this.sourceUid      = sourceUid;
        this.sourceParams   = sourceParams;
        this.brandingId     = brandingId;
        this.generatedBy    = generatedBy;
    }

    /** Sets content hash and byte size after rendering (called before persist). */
    public void setContentMeta(String contentHash, int byteSize) {
        this.contentHash = contentHash;
        this.byteSize    = byteSize;
    }
}
