package com.erp.modules.gl.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;

/**
 * One leg of a journal entry (ADR-0013 D-2c/D-3, BR-GL-08).
 * Append-only: no updated_* columns (BR-GL-02). Exactly one of debit/credit is > 0 (enforced by
 * chk_journal_line_one_side in the DB + service validation). All amounts in company base currency.
 */
@Getter
@Entity
@Table(name = "journal_lines")
public class JournalLine extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** Analysis tag; nullable. */
    @Column(name = "branch_id")
    private Long branchId;

    @Column(name = "entry_id", nullable = false, updatable = false)
    private Long entryId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "debit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    @Column(name = "credit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "line_memo", length = 255)
    private String lineMemo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected JournalLine() {
        // JPA
    }

    /** Debit line (debit_amount > 0, credit_amount = 0). */
    public static JournalLine debit(Long companyId, Long branchId, Long entryId, int lineNo,
                                    Long accountId, BigDecimal amount, String currency,
                                    String lineMemo, Long createdBy) {
        JournalLine l = new JournalLine();
        l.companyId = companyId;
        l.branchId = branchId;
        l.entryId = entryId;
        l.lineNo = lineNo;
        l.accountId = accountId;
        l.debitAmount = amount;
        l.creditAmount = BigDecimal.ZERO;
        l.currency = currency;
        l.lineMemo = lineMemo;
        l.createdBy = createdBy;
        return l;
    }

    /** Credit line (credit_amount > 0, debit_amount = 0). */
    public static JournalLine credit(Long companyId, Long branchId, Long entryId, int lineNo,
                                     Long accountId, BigDecimal amount, String currency,
                                     String lineMemo, Long createdBy) {
        JournalLine l = new JournalLine();
        l.companyId = companyId;
        l.branchId = branchId;
        l.entryId = entryId;
        l.lineNo = lineNo;
        l.accountId = accountId;
        l.debitAmount = BigDecimal.ZERO;
        l.creditAmount = amount;
        l.currency = currency;
        l.lineMemo = lineMemo;
        l.createdBy = createdBy;
        return l;
    }
}
