package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApPaymentAllocationRepository extends JpaRepository<ApPaymentAllocation, Long> {

    List<ApPaymentAllocation> findByApPaymentId(Long apPaymentId);

    List<ApPaymentAllocation> findBySupplierBillId(Long supplierBillId);
}
