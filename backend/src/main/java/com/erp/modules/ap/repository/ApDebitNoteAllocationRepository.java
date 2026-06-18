package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.ApDebitNoteAllocation;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApDebitNoteAllocationRepository
        extends JpaRepository<ApDebitNoteAllocation, Long> {

    List<ApDebitNoteAllocation> findByDebitNoteId(Long debitNoteId);

    List<ApDebitNoteAllocation> findBySupplierBillId(Long supplierBillId);

    void deleteByDebitNoteId(Long debitNoteId);

    /** Total allocated amount for a debit note (cross-check: amount − unapplied == this). */
    @Query("""
            SELECT COALESCE(SUM(a.allocatedAmount), 0)
            FROM ApDebitNoteAllocation a
            WHERE a.debitNoteId = :debitNoteId
            """)
    BigDecimal sumAllocatedByDebitNote(@Param("debitNoteId") Long debitNoteId);
}
