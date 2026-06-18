package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.SupplierContact;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierContactRepository extends JpaRepository<SupplierContact, Long> {

    Optional<SupplierContact> findByUid(String uid);

    List<SupplierContact> findBySupplierId(Long supplierId);

    List<SupplierContact> findBySupplierIdAndStatus(Long supplierId, MasterStatus status);

    boolean existsBySupplierIdAndIsPrimaryTrue(Long supplierId);

    Optional<SupplierContact> findBySupplierIdAndIsPrimaryTrue(Long supplierId);
}
