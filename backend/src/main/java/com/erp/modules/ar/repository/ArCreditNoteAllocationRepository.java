package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArCreditNoteAllocation;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArCreditNoteAllocationRepository
        extends JpaRepository<ArCreditNoteAllocation, Long> {

    List<ArCreditNoteAllocation> findByCreditNoteId(Long creditNoteId);

    List<ArCreditNoteAllocation> findByArInvoiceId(Long arInvoiceId);

    void deleteByCreditNoteId(Long creditNoteId);

    /** Total allocated amount for a credit note (cross-check: amount − unapplied == this). */
    @Query("""
            SELECT COALESCE(SUM(a.allocatedAmount), 0)
            FROM ArCreditNoteAllocation a
            WHERE a.creditNoteId = :creditNoteId
            """)
    BigDecimal sumAllocatedByCreditNote(@Param("creditNoteId") Long creditNoteId);
}
