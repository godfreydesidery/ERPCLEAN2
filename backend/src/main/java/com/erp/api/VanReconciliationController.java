package com.erp.api;

import com.erp.modules.stock.domain.dto.CreateVanReconciliationRequest;
import com.erp.modules.stock.domain.dto.EnterVanReconciliationLinesRequest;
import com.erp.modules.stock.domain.dto.VanReconciliationDto;
import com.erp.modules.stock.service.VanReconciliationService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Van-stock reconciliation REST surface (ADR-0051 D-8).
 *
 * <p>RECORD-ONLY: reconcile never posts a stock movement or GL journal (D-8.2).
 *
 * <p>Permission gates:
 * <ul>
 *   <li>STOCK.VAN_RECON.VIEW — read</li>
 *   <li>STOCK.VAN_RECON.MANAGE — create, enter lines, reconcile, cancel</li>
 * </ul>
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/van-reconciliations")
public class VanReconciliationController {

    private final VanReconciliationService vanReconciliationService;

    public VanReconciliationController(VanReconciliationService vanReconciliationService) {
        this.vanReconciliationService = vanReconciliationService;
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('STOCK.VAN_RECON.MANAGE')")
    public VanReconciliationDto create(@Valid @RequestBody CreateVanReconciliationRequest request) {
        return vanReconciliationService.create(request);
    }

    @PostMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid, 'vanreconciliation', 'STOCK.VAN_RECON.MANAGE')")
    public VanReconciliationDto enterLines(@PathVariable String uid,
                                            @Valid @RequestBody EnterVanReconciliationLinesRequest request) {
        return vanReconciliationService.enterLines(uid, request);
    }

    @PostMapping("/uid/{uid}/reconcile")
    @PreAuthorize("@perm.scoped(#uid, 'vanreconciliation', 'STOCK.VAN_RECON.MANAGE')")
    public VanReconciliationDto reconcile(@PathVariable String uid) {
        return vanReconciliationService.reconcile(uid);
    }

    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.scoped(#uid, 'vanreconciliation', 'STOCK.VAN_RECON.MANAGE')")
    public VanReconciliationDto cancel(@PathVariable String uid) {
        return vanReconciliationService.cancel(uid);
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'vanreconciliation', 'STOCK.VAN_RECON.VIEW')")
    public VanReconciliationDto getByUid(@PathVariable String uid) {
        return vanReconciliationService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('STOCK.VAN_RECON.VIEW')")
    public ApiResponse<List<VanReconciliationDto>> list(Pageable pageable) {
        Page<VanReconciliationDto> page = vanReconciliationService.list(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
