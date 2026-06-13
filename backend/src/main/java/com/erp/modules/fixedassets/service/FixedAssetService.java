package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AcquireFromBillRequest;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.PlaceInServiceRequest;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.TransferAssetRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetRequest;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FixedAssetService {

    /** Register a new DRAFT asset — allocates the FA-#### number. */
    FixedAssetDto register(RegisterAssetRequest req);

    /** Acquire from a matched AP bill — register + immediately place in service. */
    FixedAssetDto acquireFromBill(AcquireFromBillRequest req);

    FixedAssetDto getByUid(String uid);

    Page<FixedAssetDto> listByCompany(Long companyId, FixedAssetStatus status, Pageable pageable);

    /** Update non-financial fields (allowed while DRAFT). */
    FixedAssetDto update(String uid, UpdateAssetRequest req);

    /**
     * Place in service: validates, generates the schedule, posts the capitalisation journal.
     * Only valid from DRAFT.
     */
    FixedAssetDto placeInService(String uid, PlaceInServiceRequest req);

    /** Transfer (no GL) — update branch/location/costCentre. */
    FixedAssetDto transfer(String uid, TransferAssetRequest req);
}
