package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface VatReturnService {

    /** Open (create) a new DRAFT VAT return for the given company-month (FR-VAT-01). */
    VatReturnDto open(OpenVatReturnRequest req);

    /** Recompute a DRAFT return — refresh output/input/net from the live sub-ledgers (FR-VAT-02). */
    VatReturnDto recompute(String uid);

    /** File (lock) a DRAFT return: freeze figures, post GL settlement, status → FILED (FR-VAT-08). */
    VatReturnDto file(String uid, FileVatReturnRequest req);

    VatReturnDto getByUid(String uid);

    Page<VatReturnDto> listByCompany(Long companyId, Pageable pageable);
}
