package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AssetCategoryDto;
import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetCategoryRequest;
import java.util.List;

public interface AssetCategoryService {

    AssetCategoryDto create(CreateAssetCategoryRequest req);

    AssetCategoryDto getByUid(String uid);

    List<AssetCategoryDto> listByCompany(Long companyId);

    AssetCategoryDto update(String uid, UpdateAssetCategoryRequest req);

    AssetCategoryDto archive(String uid);
}
