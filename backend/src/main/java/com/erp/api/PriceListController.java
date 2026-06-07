package com.erp.api;

import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.UpdatePriceListRequest;
import com.erp.modules.products.service.PriceListService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * PriceList master administration (FR-PROD-10, ADR-0007 D-7/D-11/D-12).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Gates use {@code @perm.has} / {@code @perm.scoped} — NOT hasAuthority (brief §4.1).
 */
@RestController
@RequestMapping("/api/v1/price-lists")
public class PriceListController {

    private final PriceListService priceListService;

    public PriceListController(PriceListService priceListService) {
        this.priceListService = priceListService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('PRICELIST.VIEW')")
    public ApiResponse<List<PriceListDto>> list(@RequestParam Long companyId,
                                                @RequestParam(required = false) String q,
                                                Pageable pageable) {
        Page<PriceListDto> page = priceListService.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('PRICELIST.VIEW')")
    public PriceListDto get(@PathVariable String uid) {
        return priceListService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid,'company','PRICELIST.MANAGE')")
    public PriceListDto create(@Valid @RequestBody CreatePriceListRequest request) {
        return priceListService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pricelist','PRICELIST.MANAGE')")
    public PriceListDto update(@PathVariable String uid,
                               @Valid @RequestBody UpdatePriceListRequest request) {
        return priceListService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'pricelist','PRICELIST.MANAGE')")
    public void archive(@PathVariable String uid) {
        priceListService.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'pricelist','PRICELIST.MANAGE')")
    public void restore(@PathVariable String uid) {
        priceListService.restoreByUid(uid);
    }
}
