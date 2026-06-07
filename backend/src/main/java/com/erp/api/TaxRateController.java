package com.erp.api;

import com.erp.modules.sales.domain.dto.TaxRateDto;
import com.erp.modules.sales.domain.dto.UpdateTaxRateRequest;
import com.erp.modules.sales.service.SalesInvoiceService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tax rate administration REST controller (ADR-0008 D-5b, FR-SALES-10).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Gates use exact permission codes seeded in V5__sales.sql §8: TAXRATE.VIEW / TAXRATE.MANAGE.
 */
@RestController
@RequestMapping("/api/v1/tax-rates")
public class TaxRateController {

    private final SalesInvoiceService salesInvoiceService;

    public TaxRateController(SalesInvoiceService salesInvoiceService) {
        this.salesInvoiceService = salesInvoiceService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('TAXRATE.VIEW')")
    public List<TaxRateDto> list(@RequestParam Long companyId) {
        return salesInvoiceService.listTaxRates(companyId);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'taxrate','TAXRATE.MANAGE')")
    public TaxRateDto update(@PathVariable String uid,
                              @Valid @RequestBody UpdateTaxRateRequest request) {
        return salesInvoiceService.updateTaxRate(uid, request);
    }
}
