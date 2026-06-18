package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.OtherPartyAddress;
import com.erp.modules.parties.domain.enums.AddressRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OtherPartyAddressRepository extends JpaRepository<OtherPartyAddress, Long> {

    Optional<OtherPartyAddress> findByUid(String uid);

    List<OtherPartyAddress> findByOtherPartyId(Long otherPartyId);

    List<OtherPartyAddress> findByOtherPartyIdAndAddressRole(Long otherPartyId, AddressRole addressRole);

    Optional<OtherPartyAddress> findByOtherPartyIdAndAddressRoleAndIsDefaultTrue(Long otherPartyId,
                                                                                  AddressRole addressRole);
}
