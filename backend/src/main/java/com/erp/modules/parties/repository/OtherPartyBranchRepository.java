package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.OtherPartyBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtherPartyBranchRepository extends JpaRepository<OtherPartyBranch, Long> {

    Optional<OtherPartyBranch> findByOtherPartyIdAndBranchId(Long otherPartyId, Long branchId);

    List<OtherPartyBranch> findByOtherPartyId(Long otherPartyId);

    List<OtherPartyBranch> findByBranchId(Long branchId);

    void deleteByOtherPartyIdAndBranchId(Long otherPartyId, Long branchId);
}
