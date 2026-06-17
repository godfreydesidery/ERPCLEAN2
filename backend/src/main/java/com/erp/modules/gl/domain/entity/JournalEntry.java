package com.erp.modules.gl.domain.entity;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * One balanced journal transaction (ADR-0013 D-2c/D-3, BR-GL-01/BR-GL-02).
 * Append-only: no updated_* columns. Holds posting_date (business date) distinct from posted_at
 * (system timestamp). source_ref = invoice uid for SALES/SALES_REVERSAL entries (D-6).
 * reversal_of_id = self-FK to the entry this reversal negates (BR-GL-11).
 */
@Getter
@Entity
@Table(name = "journal_entries")
public class JournalEntry extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Nullable: manual company-level journal has no originating branch. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "batch_id", nullable = false, updatable = false)
    private Long batchId;

    @Column(name = "entry_no", nullable = false)
    @Setter
    private int entryNo = 1;

    @Column(name = "posting_date", nullable = false)
    private LocalDate postingDate;

    @Column(name = "fiscal_period_id", nullable = false, updatable = false)
    @Setter
    private Long fiscalPeriodId;

    @Column(name = "description", nullable = false, length = 255)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private JournalSourceType sourceType;

    /** The source document uid (e.g. invoice uid for SALES entries); NULL for free manual. */
    @Column(name = "source_ref", length = 60)
    private String sourceRef;

    /** Self-FK: set on reversing entry to the id of the entry it negates (BR-GL-11). */
    @Column(name = "reversal_of_id")
    @Setter
    private Long reversalOfId;

    /**
     * Self-FK (P2-M1): set on the original entry when it has been reversed. Points to the
     * reversing entry's id (complement of reversal_of_id).
     */
    @Column(name = "reversed_by_entry_id")
    @Setter
    private Long reversedByEntryId;

    /** Convenience flag (P2-M1): true when a reversing entry referencing this one exists. */
    @Column(name = "reversed", nullable = false)
    @Setter
    private boolean reversed = false;

    /**
     * Informational source-document currency (P2-M1). The ledger remains single-functional-currency
     * (ADR-0036); this field records the face currency of the originating document for reporting.
     * Mapped via {@link com.erp.platform.common.money.CurrencyCodeConverter} (autoApply=true).
     */
    @Column(name = "header_currency", length = 3)
    @Setter
    private CurrencyCode headerCurrency;

    /** Denormalised Σ debit_amount across all lines in this entry (P2-M1). */
    @Column(name = "total_debit", precision = 19, scale = 4)
    @Setter
    private BigDecimal totalDebit;

    /** Denormalised Σ credit_amount across all lines in this entry (P2-M1). */
    @Column(name = "total_credit", precision = 19, scale = 4)
    @Setter
    private BigDecimal totalCredit;

    @Column(name = "posted_at", nullable = false, updatable = false)
    private Instant postedAt = Instant.now();

    /** NULL for SYSTEM auto-poster (FR-GL-19). */
    @Column(name = "posted_by")
    private Long postedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected JournalEntry() {
        // JPA
    }

    public JournalEntry(Long companyId, Long branchId, Long batchId,
                        LocalDate postingDate, String description,
                        JournalSourceType sourceType, String sourceRef,
                        Long postedBy, Long createdBy) {
        this.companyId = companyId;
        this.branchId = branchId;
        this.batchId = batchId;
        this.postingDate = postingDate;
        this.description = description;
        this.sourceType = sourceType;
        this.sourceRef = sourceRef;
        this.postedBy = postedBy;
        this.createdBy = createdBy;
    }
}
