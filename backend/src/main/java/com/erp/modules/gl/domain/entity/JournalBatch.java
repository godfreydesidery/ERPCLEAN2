package com.erp.modules.gl.domain.entity;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;

/**
 * The numbered batch container for journal entries (ADR-0013 D-2c, D-3).
 * Append-only: no updated_* columns (BR-GL-02). batch_number = JB-#### from code_sequence.
 */
@Getter
@Entity
@Table(name = "journal_batches")
public class JournalBatch extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Nullable: manual company-level journals have no originating branch. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "batch_number", nullable = false, length = 30)
    private String batchNumber;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private JournalSourceType sourceType;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt = Instant.now();

    /** NULL for the SYSTEM auto-poster (FR-GL-19). */
    @Column(name = "posted_by")
    private Long postedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected JournalBatch() {
        // JPA
    }

    public JournalBatch(Long companyId, Long branchId, String batchNumber,
                        JournalSourceType sourceType, String description,
                        Long postedBy, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.batchNumber = batchNumber;
        this.sourceType = sourceType;
        this.description = description;
        this.postedBy = postedBy;
        this.createdBy = createdBy;
    }
}
