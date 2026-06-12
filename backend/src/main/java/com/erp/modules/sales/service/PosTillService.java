package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.PosTillDto;
import java.util.List;

/**
 * CRUD for POS tills (ADR-0029 D-5).
 */
public interface PosTillService {

    PosTillDto createTill(CreatePosTillRequest request);

    PosTillDto getTillByUid(String uid);

    List<PosTillDto> listTillsByBranch(Long companyId, Long branchId);

    void deactivateTill(String uid);
}
