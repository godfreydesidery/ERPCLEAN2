package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.dto.UpdateSupplierRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupplierService {

    SupplierDto create(CreateSupplierRequest request);

    SupplierDto getByUid(String uid);

    Page<SupplierDto> list(Long companyId, String q, Pageable pageable);

    SupplierDto updateByUid(String uid, UpdateSupplierRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest request);

    void removeBranch(String uid, String branchUid);

    List<PartyBranchDto> listBranches(String uid);
}
