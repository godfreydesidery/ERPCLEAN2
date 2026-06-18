package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.OtherPartyContact;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtherPartyContactRepository extends JpaRepository<OtherPartyContact, Long> {

    Optional<OtherPartyContact> findByUid(String uid);

    List<OtherPartyContact> findByOtherPartyId(Long otherPartyId);

    List<OtherPartyContact> findByOtherPartyIdAndStatus(Long otherPartyId, MasterStatus status);

    boolean existsByOtherPartyIdAndIsPrimaryTrue(Long otherPartyId);

    Optional<OtherPartyContact> findByOtherPartyIdAndIsPrimaryTrue(Long otherPartyId);
}
