package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.BillMatchResultDto;

public interface BillMatchService {

    /**
     * Run the 3-way match on a DRAFT bill. On all-lines-within-tolerance → MATCHED + posts to GL.
     * Any over-tolerance line → HELD, no GL post. AP.BILL.MATCH.
     */
    BillMatchResultDto runMatch(String billUid);

    /**
     * Accept the variance on one held bill line. When all lines become MATCHED/VARIANCE_ACCEPTED
     * → bill transitions to MATCHED and posts to GL synchronously. AP.BILL.MATCH.
     */
    BillMatchResultDto acceptVariance(String billUid, String billLineUid);
}
