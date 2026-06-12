package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AssetRevaluationDto;
import com.erp.modules.fixedassets.domain.dto.RevalueAssetRequest;
import java.util.List;

public interface AssetRevaluationService {

    /** Revalue an IN_SERVICE asset — posts the GL journal, updates carrying cost, regenerates schedule. */
    AssetRevaluationDto revalue(String assetUid, RevalueAssetRequest req);

    /** List all revaluations for an asset. */
    List<AssetRevaluationDto> listByAsset(String assetUid);
}
