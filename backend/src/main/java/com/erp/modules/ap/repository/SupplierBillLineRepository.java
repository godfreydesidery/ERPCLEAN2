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

    /**
     * Company-scoped id lookup, for resolving a line referenced by internal id from an already-loaded
     * aggregate. Prefer this over {@code findById}: the bare finder is a confused-deputy risk and is
     * what TenantScopingRulesTest exists to prevent — the company must come from the LOADED entity,
     * never from a caller-supplied value.
     */
    Optional<SupplierBillLine> findByCompanyIdAndId(Long companyId, Long id);

    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM SupplierBillLine l WHERE l.supplierBillId = :billId")
    int findMaxLineNo(@Param("billId") Long billId);
}
