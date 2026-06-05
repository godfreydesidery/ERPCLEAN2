package com.erp.modules.iam.service;

import com.erp.modules.iam.domain.dto.BranchDto;
import com.erp.modules.iam.domain.dto.CreateBranchRequest;
import com.erp.modules.iam.domain.dto.UpdateBranchRequest;
import java.util.List;

/**
 * Branch administration (DATA-MODEL §1.3). The company-default-branch invariant (BR-2: at most one
 * default per company) is owned here, in {@link #setDefault}, and nowhere else (SRP).
 */
public interface BranchService {

    BranchDto create(CreateBranchRequest request);

    BranchDto getByUid(String uid);

    List<BranchDto> listByCompanyUid(String companyUid);

    BranchDto updateByUid(String uid, UpdateBranchRequest request);

    /** Make this branch its company's default, clearing the previous default in the same TX. */
    BranchDto setDefault(String uid);

    void archiveByUid(String uid);
}
