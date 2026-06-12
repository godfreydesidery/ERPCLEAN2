package com.erp.api;

import com.erp.modules.sales.domain.dto.BlanketOrderDto;
import com.erp.modules.sales.domain.dto.CreateBlanketOrderRequest;
import com.erp.modules.sales.domain.dto.DrawBlanketRequest;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.service.BlanketOrderService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Blanket order lifecycle (ADR-0029 D-7).
 * Permissions seeded in V45__sales_blanket_standing.sql.
 */
@RestController
@RequestMapping("/api/v1/blanket-orders")
public class BlanketOrderController {

    private final BlanketOrderService blanketOrderService;

    public BlanketOrderController(BlanketOrderService blanketOrderService) {
        this.blanketOrderService = blanketOrderService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid(),'company','SALES.BLANKET.CREATE')")
    public BlanketOrderDto create(@Valid @RequestBody CreateBlanketOrderRequest request) {
        return blanketOrderService.createBlanket(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'blanketorder','SALES.BLANKET.VIEW')")
    public BlanketOrderDto getByUid(@PathVariable String uid) {
        return blanketOrderService.getBlanketByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.BLANKET.VIEW')")
    public ApiResponse<List<BlanketOrderDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<BlanketOrderDto> page = blanketOrderService.listBlankets(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'blanketorder','SALES.BLANKET.MANAGE')")
    public void cancel(@PathVariable String uid) {
        blanketOrderService.cancelBlanket(uid);
    }

    @PostMapping("/uid/{uid}/draw")
    @PreAuthorize("@perm.scoped(#uid,'blanketorder','SALES.BLANKET.MANAGE')")
    public SalesOrderDto draw(@PathVariable String uid,
                               @Valid @RequestBody DrawBlanketRequest request) {
        return blanketOrderService.drawAgainstBlanket(request);
    }
}
