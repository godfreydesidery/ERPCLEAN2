package com.erp.modules.budgeting.domain.entity;

import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One (account, fiscal period, amount) within a budget version (ADR-0034 D-4c, BR-BUD-04).
 *
 * <p>Lines are replaced wholesale per version edit; the version's {@code @Version} (on
 * {@link BudgetVersion}) guards concurrency — lines themselves do not carry their own
 * business-level optimistic lock, but the {@code version} column IS required because
 * {@link UidEntity} inherits {@code @Version} and Hibernate ddl-validate fails without it.
 *
 * <p>Lines are editable ONLY while the owning version is DRAFT (service check, BR-BUD-06).
 * Amount is non-negative (HALF_UP), base currency (BR-BUD-09/10). The sign for variance
 * computation comes from the account's {@code normal_balance} / {@code account_type} (BR-BUD-14).
 */
@Getter
@Entity
@Table(name = "budget_lines")
public class BudgetLine extends UidEntity {

    @Column(name = "budget_version_id", nullable = false, updatable = false)
    private Long budgetVersionId;

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    @Column(name = "account_id", nullable = false)
    @Setter
    private Long accountId;

    @Column(name = "fiscal_period_id", nullable = false)
    @Setter
    private Long fiscalPeriodId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    @Setter
    private BigDecimal amount;

    @Column(name = "currency", nullable = false, length = 3)
    @Setter
    private CurrencyCode currency;

    @Column(name = "line_memo", length = 255)
    @Setter
    private String lineMemo;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    protected BudgetLine() {
        // JPA
    }

    public BudgetLine(Long budgetVersionId, Long companyId, Long accountId,
                      Long fiscalPeriodId, BigDecimal amount, String currency,
                      String lineMemo, Long createdBy) {
        this.budgetVersionId = budgetVersionId;
        this.companyId       = companyId;
        this.accountId       = accountId;
        this.fiscalPeriodId  = fiscalPeriodId;
        this.amount          = amount;
        this.currency        = CurrencyCode.of(currency);
        this.lineMemo        = lineMemo;
        this.createdBy       = createdBy;
    }
}
