package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.SupplierBillLine;
import java.math.BigDecimal;
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

    /**
     * How much of one goods-receipt line is already claimed by OTHER bills (AP-05).
     *
     * <p>Every bill line attached to the same receipt line carries its quantity in that receipt
     * line's unit (the unit printed on the GRN), so the quantities add up directly. A DRAFT bill has
     * not been through the match yet and claims nothing; every other bill — held, matched or paid —
     * does. A deleted draft or held bill is gone from the table and so no longer claims anything.
     */
    @Query("""
            SELECT COALESCE(SUM(l.billedQty), 0)
            FROM SupplierBillLine l, SupplierBill b
            WHERE b.id = l.supplierBillId
              AND l.companyId = :companyId
              AND l.grLineUid = :grLineUid
              AND b.id <> :excludeBillId
              AND b.status <> com.erp.modules.ap.domain.enums.SupplierBillStatus.DRAFT
            """)
    BigDecimal sumBilledQtyOnOtherBills(@Param("companyId") Long companyId,
                                        @Param("grLineUid") String grLineUid,
                                        @Param("excludeBillId") Long excludeBillId);
}
