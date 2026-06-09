package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArReceiptAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArReceiptAllocationRepository extends JpaRepository<ArReceiptAllocation, Long> {

    List<ArReceiptAllocation> findByReceiptId(Long receiptId);

    List<ArReceiptAllocation> findByArInvoiceId(Long arInvoiceId);

    void deleteByReceiptId(Long receiptId);

    /** Total allocated amount for a receipt (cross-check: amount − unallocated == this). */
    @Query("SELECT COALESCE(SUM(a.allocatedAmount), 0) FROM ArReceiptAllocation a WHERE a.receiptId = :receiptId")
    java.math.BigDecimal sumAllocatedByReceipt(@Param("receiptId") Long receiptId);
}
