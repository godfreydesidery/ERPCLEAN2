package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.SupplierAddress;
import com.erp.modules.parties.domain.enums.AddressRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierAddressRepository extends JpaRepository<SupplierAddress, Long> {

    Optional<SupplierAddress> findByUid(String uid);

    List<SupplierAddress> findBySupplierId(Long supplierId);

    List<SupplierAddress> findBySupplierIdAndAddressRole(Long supplierId, AddressRole addressRole);

    Optional<SupplierAddress> findBySupplierIdAndAddressRoleAndIsDefaultTrue(Long supplierId,
                                                                              AddressRole addressRole);
}
