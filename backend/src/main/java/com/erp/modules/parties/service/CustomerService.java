package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerService {

    CustomerDto create(CreateCustomerRequest request);

    CustomerDto getByUid(String uid);

    Page<CustomerDto> list(Long companyId, String q, Pageable pageable);

    /**
     * As {@link #list(Long, String, Pageable)}, narrowed to one {@link CustomerKind} when
     * {@code kind} is non-null (POS-14: the till asks for {@code CASH_WALK_IN}).
     */
    default Page<CustomerDto> list(Long companyId, String q, CustomerKind kind, Pageable pageable) {
        return list(companyId, q, pageable);
    }

    CustomerDto updateByUid(String uid, UpdateCustomerRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest request);

    void removeBranch(String uid, String branchUid);

    List<PartyBranchDto> listBranches(String uid);
}
