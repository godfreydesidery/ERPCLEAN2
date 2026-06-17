package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.CustomerContact;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerContactRepository extends JpaRepository<CustomerContact, Long> {

    Optional<CustomerContact> findByUid(String uid);

    List<CustomerContact> findByCustomerId(Long customerId);

    List<CustomerContact> findByCustomerIdAndStatus(Long customerId, MasterStatus status);

    boolean existsByCustomerIdAndIsPrimaryTrue(Long customerId);

    Optional<CustomerContact> findByCustomerIdAndIsPrimaryTrue(Long customerId);
}
