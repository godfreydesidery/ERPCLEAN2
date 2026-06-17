package com.erp.modules.parties.domain.entity;

import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.domain.UidEntity;
import com.erp.platform.common.money.CurrencyCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * Supplier bank account — addressable child sub-master with uid (D-4, ADR-0040).
 * at-most-one is_default enforced by partial unique index uq_supplier_bank_account_default.
 * DB CHECK: account_no IS NOT NULL OR iban IS NOT NULL.
 */
@Getter
@Entity
@Table(name = "supplier_bank_accounts")
public class SupplierBankAccount extends UidEntity {

    @Column(name = "company_id", nullable = false, updatable = false)
    private Long companyId;

    /** FK → suppliers(id); tenant-scoped ownership. Never updated. */
    @Column(name = "supplier_id", nullable = false, updatable = false)
    private Long supplierId;

    @Column(name = "bank_name", nullable = false, length = 120)
    @Setter
    private String bankName;

    @Column(name = "account_name", nullable = false, length = 120)
    @Setter
    private String accountName;

    /** At least one of account_no / iban must be non-null (DB CHECK chk_sba_account_or_iban). */
    @Column(name = "account_no", length = 60)
    @Setter
    private String accountNo;

    @Column(name = "bank_branch", length = 120)
    @Setter
    private String bankBranch;

    @Column(name = "iban", length = 34)
    @Setter
    private String iban;

    @Column(name = "swift_bic", length = 11)
    @Setter
    private String swiftBic;

    /** Optional currency — null means multi-currency or unspecified. */
    @Column(name = "currency", length = 3)
    @Setter
    private CurrencyCode currency;

    /** At most one default per supplier — partial unique index enforces. */
    @Column(name = "is_default", nullable = false)
    @Setter
    private boolean isDefault = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    @Setter
    private MasterStatus status = MasterStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "updated_at")
    @Setter
    private Instant updatedAt;

    @Column(name = "updated_by")
    @Setter
    private Long updatedBy;

    protected SupplierBankAccount() {
        // JPA
    }

    public SupplierBankAccount(Long companyId, Long supplierId,
                                String bankName, String accountName,
                                String accountNo, String iban,
                                Long createdBy) {
        this.companyId   = companyId;
        this.supplierId  = supplierId;
        this.bankName    = bankName;
        this.accountName = accountName;
        this.accountNo   = accountNo;
        this.iban        = iban;
        this.createdBy   = createdBy;
    }
}
