package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.SupplierBankAccount;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Repository for SupplierBankAccount (D-4). */
public interface SupplierBankAccountRepository extends JpaRepository<SupplierBankAccount, Long> {

    Optional<SupplierBankAccount> findByUid(String uid);

    List<SupplierBankAccount> findBySupplierId(Long supplierId);

    List<SupplierBankAccount> findBySupplierIdAndStatus(Long supplierId, MasterStatus status);

    Optional<SupplierBankAccount> findBySupplierIdAndIsDefaultTrue(Long supplierId);

    boolean existsBySupplierIdAndIsDefaultTrue(Long supplierId);
}
