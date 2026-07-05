package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.MassPriceChangeRequest;
import com.erp.modules.products.domain.dto.MassPriceChangeResult;

/** Rule-based mass price change over a whole price list (see {@link MassPriceChangeRequest}). */
public interface PriceMassChangeService {

    MassPriceChangeResult apply(MassPriceChangeRequest request);
}
