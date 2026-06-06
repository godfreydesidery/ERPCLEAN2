package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.CustomerBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerBranchRepository extends JpaRepository<CustomerBranch, Long> {

    Optional<CustomerBranch> findByCustomerIdAndBranchId(Long customerId, Long branchId);

    List<CustomerBranch> findByCustomerId(Long customerId);

    List<CustomerBranch> findByBranchId(Long branchId);

    void deleteByCustomerIdAndBranchId(Long customerId, Long branchId);
}
