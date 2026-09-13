package com.erp.api;

import com.erp.modules.stock.domain.dto.ItemInquiryDto;
import com.erp.modules.stock.service.ItemInquiryQuery;
import com.erp.platform.security.PermissionChecks;
import com.erp.platform.security.RequestContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Item Inquiry — the counter lookup (K-2026-08-30 #3: "to include code, description, cost, selling
 * and available quantity").
 *
 * <p><b>Permission.</b> {@code PRODUCT.VIEW} and {@code STOCK.VIEW}, both of which every
 * counter-facing role already holds. It is deliberately NOT gated on
 * {@code INVENTORY.VALUATION.VIEW} like the product-stock registers: those return the whole
 * catalogue's buying prices in one call, which is a different disclosure from answering one
 * customer's question about one item.
 *
 * <p>The standing rule that a cashier must not learn an item's margin is kept, as a hidden COLUMN:
 * cost is read only for callers who additionally hold {@code INVENTORY.VALUATION.VIEW}, and the
 * response states which of the two happened so a withheld cost is never mistaken for an item nobody
 * has costed.
 */
@RestController
@RequestMapping("/api/v1/stock/item-inquiry")
public class ItemInquiryController {

    private final ItemInquiryQuery  query;
    private final PermissionChecks  perm;

    public ItemInquiryController(ItemInquiryQuery query, PermissionChecks perm) {
        this.query = query;
        this.perm  = perm;
    }

    /**
     * @param q         item code, part of the description, or a scanned barcode
     * @param branchUid optional; omitted sums every branch in the company
     */
    @GetMapping
    @PreAuthorize("@perm.has('PRODUCT.VIEW') and @perm.has('STOCK.VIEW')")
    public ItemInquiryDto inquire(@RequestParam String q,
                                   @RequestParam(required = false) String branchUid) {
        boolean includeCost = perm.has("INVENTORY.VALUATION.VIEW");
        return query.inquire(RequestContext.get().companyId(), q, branchUid, includeCost);
    }
}
