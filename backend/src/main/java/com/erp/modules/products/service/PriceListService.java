package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.UpdatePriceListRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface PriceListService {

    PriceListDto create(CreatePriceListRequest req);

    PriceListDto getByUid(String uid);

    Page<PriceListDto> list(Long companyId, String q, Pageable pageable);

    /**
     * Renames the list and applies whatever metadata the request carries. The metadata fields are a
     * partial update: an omitted field keeps its stored value rather than being cleared, because the
     * screens that call this send only the fields they own (rename sends the name and the VAT
     * toggle; set-default sends the name and the flag).
     */
    PriceListDto updateByUid(String uid, UpdatePriceListRequest req);

    /**
     * Archives the list and clears its default flag — price resolution only considers ACTIVE lists,
     * so an archived default would leave the company looking configured while resolving nothing.
     */
    void archiveByUid(String uid);

    void restoreByUid(String uid);

    /**
     * Makes this the company's one default price list, clearing the flag on every other list in the
     * company. Reports read the default list to resolve selling prices, so exactly one is the only
     * answer that is meaningful.
     */
    PriceListDto setDefaultByUid(String uid);
}
