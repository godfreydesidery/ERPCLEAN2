package com.erp.api;

import com.erp.modules.products.domain.dto.MassPriceChangeRequest;
import com.erp.modules.products.domain.dto.MassPriceChangeResult;
import com.erp.modules.products.service.PriceMassChangeService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rule-based mass price change (a "price change" mass operation): apply a percentage/amount/set rule
 * to every price on a list. Pass {@code dryRun=true} in the body to preview counts + samples without
 * writing. Gated on {@code PRICE.MASS_UPDATE}; the service asserts company scope from the loaded
 * price list.
 */
@RestController
@RequestMapping("/api/v1/prices")
public class PriceMassChangeController {

    private final PriceMassChangeService priceMassChange;

    public PriceMassChangeController(PriceMassChangeService priceMassChange) {
        this.priceMassChange = priceMassChange;
    }

    @PostMapping("/mass-change")
    @PreAuthorize("@perm.has('PRICE.MASS_UPDATE')")
    public MassPriceChangeResult massChange(@Valid @RequestBody MassPriceChangeRequest request) {
        return priceMassChange.apply(request);
    }
}
