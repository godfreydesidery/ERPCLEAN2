package com.erp.modules.parties.domain.entity;

import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Supplier master (FR-PARTY-02, ADR-0006 D-2).
 * Sub-kind: {@link SupplierKind#GOODS} or {@link SupplierKind#SERVICE}.
 */
@Getter
@Entity
@Table(name = "suppliers")
public class Supplier extends PartyBase {

    @Enumerated(EnumType.STRING)
    @Column(name = "supplier_kind", nullable = false, length = 20)
    @Setter
    private SupplierKind supplierKind;

    /** Optional payment terms in days (mirrors Customer.paymentTermsDays; unblocks AP due-date derivation, X10). */
    @Column(name = "payment_terms_days")
    @Setter
    private Integer paymentTermsDays;

    /**
     * Soft-FK to payment_terms(id) — scalar, no @ManyToOne (cross-module soft-FK convention).
     * When set, overrides payment_terms_days for due-date derivation (D-2, ADR-0040).
     * payment_terms_days is retained as deprecated fallback.
     */
    @Column(name = "payment_terms_id")
    @Setter
    private Long paymentTermsId;

    protected Supplier() {
        // JPA
    }

    public Supplier(Long companyId, String code, PartyType partyType, String displayName,
                    SupplierKind supplierKind, Long createdBy) {
        super(companyId, code, partyType, displayName, createdBy);
        this.supplierKind = supplierKind;
    }
}
