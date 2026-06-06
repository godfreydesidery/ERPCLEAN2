package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateOtherPartyRequest;
import com.erp.modules.parties.domain.dto.OtherPartyDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateOtherPartyRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface OtherPartyService {

    OtherPartyDto create(CreateOtherPartyRequest request);

    OtherPartyDto getByUid(String uid);

    Page<OtherPartyDto> list(Long companyId, String q, Pageable pageable);

    OtherPartyDto updateByUid(String uid, UpdateOtherPartyRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest request);

    void removeBranch(String uid, String branchUid);

    List<PartyBranchDto> listBranches(String uid);
}
