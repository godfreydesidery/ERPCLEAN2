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

    /**
     * ACC-07: record paying a FILED return's net VAT to TRA — DR VAT Due / CR the chosen cash/bank
     * account (a cash transaction). Part payments accumulate on {@code paidAmount}; the total can
     * never exceed the filed net payable.
     */
    VatReturnDto recordPayment(String uid, com.erp.modules.tax.domain.dto.RecordTaxPaymentRequest req);

    VatReturnDto getByUid(String uid);

    Page<VatReturnDto> listByCompany(Long companyId, Pageable pageable);
}
