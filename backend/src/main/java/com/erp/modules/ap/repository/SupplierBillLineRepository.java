package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.SupplierBillLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SupplierBillLineRepository extends JpaRepository<SupplierBillLine, Long> {

    List<SupplierBillLine> findBySupplierBillIdOrderByLineNo(Long supplierBillId);

    Optional<SupplierBillLine> findByUid(String uid);

    Optional<SupplierBillLine> findBySupplierBillIdAndUid(Long supplierBillId, String uid);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM SupplierBillLine l WHERE l.supplierBillId = :billId")
    int findMaxLineNo(@Param("billId") Long billId);
}
