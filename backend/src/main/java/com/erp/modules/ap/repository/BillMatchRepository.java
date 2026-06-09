package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.BillMatch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BillMatchRepository extends JpaRepository<BillMatch, Long> {

    List<BillMatch> findBySupplierBillId(Long supplierBillId);

    Optional<BillMatch> findBySupplierBillLineId(Long supplierBillLineId);
}
