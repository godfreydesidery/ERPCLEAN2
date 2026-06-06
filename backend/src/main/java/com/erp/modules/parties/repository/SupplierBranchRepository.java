package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.SupplierBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierBranchRepository extends JpaRepository<SupplierBranch, Long> {

    Optional<SupplierBranch> findBySupplierIdAndBranchId(Long supplierId, Long branchId);

    List<SupplierBranch> findBySupplierId(Long supplierId);

    List<SupplierBranch> findByBranchId(Long branchId);

    void deleteBySupplierIdAndBranchId(Long supplierId, Long branchId);
}
