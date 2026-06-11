package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AssetDisposalDto;
import com.erp.modules.fixedassets.domain.dto.DisposeAssetRequest;
import com.erp.modules.fixedassets.domain.dto.WriteOffAssetRequest;

public interface AssetDisposalService {

    /** Dispose of an asset by SALE — proceeds >= 0; gain/loss computed. */
    AssetDisposalDto dispose(String assetUid, DisposeAssetRequest req);

    /** Write off an asset — proceeds = 0; loss = full NBV. */
    AssetDisposalDto writeOff(String assetUid, WriteOffAssetRequest req);
}
