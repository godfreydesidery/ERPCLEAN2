package com.erp.api;

import com.erp.modules.products.domain.dto.ResolveUnitPricesRequest;
import com.erp.modules.products.domain.dto.ResolvedUnitPriceDto;
import com.erp.modules.products.service.UnitPriceLookupService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Batch price READ for pricing screens — the one place a client can ask "what will the server
 * charge for these products, in this unit?".
 *
 * <p>Before this endpoint the resolver was exposed nowhere, so the Flutter till, the web POS and the
 * quotation screen each approximated the rules and all three disagreed with the posted invoice.
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 *
 * <p>POST, not GET: a POS search page prices a whole result set at once and ~60 ULIDs overflow a
 * comfortable query string. It is still a pure read — nothing is written, and the gate is a VIEW
 * permission.
 */
@RestController
@RequestMapping("/api/v1/product-prices")
public class ProductPriceLookupController {

    private final UnitPriceLookupService unitPriceLookupService;

    public ProductPriceLookupController(UnitPriceLookupService unitPriceLookupService) {
        this.unitPriceLookupService = unitPriceLookupService;
    }

    /**
     * Resolve prices for up to 200 products in one request.
     *
     * <p>Gated on {@code PRODUCT.VIEW} with the same {@code hasOrMember} widening as
     * {@code ProductController#list}: this endpoint is the companion read to that product list, and
     * a caller who may see a product must be able to see its price or the screen half-loads. The
     * service applies its own tenant scope from the request context, so the widening stays inside
     * the tenant. {@code PRODUCT.VIEW} is seeded and held by the CASHIER, SALESPERSON,
     * SALES_MANAGER and FIELD_SALES_AGENT bundles — the till and the quotation screen included.
     */
    @PostMapping("/resolve")
    @PreAuthorize("@perm.hasOrMember('PRODUCT.VIEW')")
    public List<ResolvedUnitPriceDto> resolve(@Valid @RequestBody ResolveUnitPricesRequest request) {
        return unitPriceLookupService.resolve(request);
    }
}
