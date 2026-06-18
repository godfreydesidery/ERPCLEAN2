package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.CustomerAddress;
import com.erp.modules.parties.domain.enums.AddressRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerAddressRepository extends JpaRepository<CustomerAddress, Long> {

    Optional<CustomerAddress> findByUid(String uid);

    List<CustomerAddress> findByCustomerId(Long customerId);

    List<CustomerAddress> findByCustomerIdAndAddressRole(Long customerId, AddressRole addressRole);

    Optional<CustomerAddress> findByCustomerIdAndAddressRoleAndIsDefaultTrue(Long customerId,
                                                                              AddressRole addressRole);
}
