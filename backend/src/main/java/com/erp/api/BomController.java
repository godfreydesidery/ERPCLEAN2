package com.erp.api;

import com.erp.modules.products.domain.dto.ActivateBomRequest;
import com.erp.modules.products.domain.dto.AddBomComponentRequest;
import com.erp.modules.products.domain.dto.BomComponentDto;
import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import com.erp.modules.products.domain.dto.BomDto;
import com.erp.modules.products.domain.dto.BomExplosionResultDto;
import com.erp.modules.products.domain.dto.CreateBomRequest;
import com.erp.modules.products.domain.dto.UpdateBomComponentRequest;
import com.erp.modules.products.domain.dto.UpdateBomRequest;
import com.erp.modules.products.domain.dto.WhereUsedRowDto;
import com.erp.modules.products.domain.dto.WhereUsedTreeDto;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.service.BomComponentService;
import com.erp.modules.products.service.BomCostRollUpService;
import com.erp.modules.products.service.BomExplosionService;
import com.erp.modules.products.service.BomService;
import com.erp.modules.products.service.BomWhereUsedService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Multi-level BOM REST controller (ADR-0026 D-1, API surface, FR-BOM-01..21).
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission gates use {@code @perm.has} / {@code @perm.scoped} — NEVER {@code hasAuthority}
 * (PROJECT-CONVENTIONS §RBAC). {@code ScopeGuard.assertCanActIn} is enforced on every read path
 * in the delegated services (NFR-BOM-06).
 *
 * <p>URL layout:
 * <ul>
 *   <li>{@code GET  /api/v1/boms}                                   — list / filter headers</li>
 *   <li>{@code GET  /api/v1/boms/uid/{uid}}                         — get header + components</li>
 *   <li>{@code POST /api/v1/boms}                                   — create DRAFT</li>
 *   <li>{@code PUT  /api/v1/boms/uid/{uid}}                         — update metadata</li>
 *   <li>{@code POST /api/v1/boms/uid/{uid}/components}              — add component</li>
 *   <li>{@code PUT  /api/v1/boms/uid/{uid}/components/{cUid}}       — update component</li>
 *   <li>{@code DELETE /api/v1/boms/uid/{uid}/components/{cUid}}     — remove component</li>
 *   <li>{@code POST /api/v1/boms/uid/{uid}/activate}                — activate (supersedes prior)</li>
 *   <li>{@code POST /api/v1/boms/uid/{uid}/archive}                 — archive</li>
 *   <li>{@code GET  /api/v1/boms/explode}                           — multi-level explosion</li>
 *   <li>{@code GET  /api/v1/boms/where-used/{componentProductUid}}  — where-used</li>
 *   <li>{@code GET  /api/v1/boms/cost-roll-up}                      — derived standard cost</li>
 *   <li>{@code POST /api/v1/products/uid/{uid}/promote-recipe-to-bom} — promote legacy recipe</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/boms")
public class BomController {

    private final BomService bomService;
    private final BomComponentService bomComponentService;
    private final BomExplosionService bomExplosionService;
    private final BomWhereUsedService bomWhereUsedService;
    private final BomCostRollUpService bomCostRollUpService;
    private final ScopeGuard scopeGuard;

    public BomController(BomService bomService,
                         BomComponentService bomComponentService,
                         BomExplosionService bomExplosionService,
                         BomWhereUsedService bomWhereUsedService,
                         BomCostRollUpService bomCostRollUpService,
                         ScopeGuard scopeGuard) {
        this.bomService = bomService;
        this.bomComponentService = bomComponentService;
        this.bomExplosionService = bomExplosionService;
        this.bomWhereUsedService = bomWhereUsedService;
        this.bomCostRollUpService = bomCostRollUpService;
        this.scopeGuard = scopeGuard;
    }

    // -------------------------------------------------------------------------
    // BOM header CRUD
    // -------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("@perm.has('BOM.VIEW')")
    public ApiResponse<List<BomDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) String parentProductUid,
            @RequestParam(required = false) BomStatus status,
            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<BomDto> page = bomService.list(companyId, parentProductUid, status, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.VIEW')")
    public BomDto get(@PathVariable String uid) {
        return bomService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('BOM.MANAGE')")
    public BomDto create(
            @RequestParam Long companyId,
            @Valid @RequestBody CreateBomRequest request) {
        return bomService.create(companyId, request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.MANAGE')")
    public BomDto update(@PathVariable String uid,
                         @Valid @RequestBody UpdateBomRequest request) {
        return bomService.update(uid, request);
    }

    // -------------------------------------------------------------------------
    // Component lines
    // -------------------------------------------------------------------------

    @PostMapping("/uid/{uid}/components")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.MANAGE')")
    public BomComponentDto addComponent(@PathVariable String uid,
                                        @Valid @RequestBody AddBomComponentRequest request) {
        return bomComponentService.add(uid, request);
    }

    @PutMapping("/uid/{uid}/components/{componentUid}")
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.MANAGE')")
    public BomComponentDto updateComponent(@PathVariable String uid,
                                            @PathVariable String componentUid,
                                            @Valid @RequestBody UpdateBomComponentRequest request) {
        return bomComponentService.update(uid, componentUid, request);
    }

    @DeleteMapping("/uid/{uid}/components/{componentUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.MANAGE')")
    public void removeComponent(@PathVariable String uid,
                                @PathVariable String componentUid) {
        bomComponentService.remove(uid, componentUid);
    }

    @GetMapping("/uid/{uid}/components")
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.VIEW')")
    public List<BomComponentDto> listComponents(@PathVariable String uid) {
        return bomComponentService.list(uid);
    }

    // -------------------------------------------------------------------------
    // Lifecycle transitions
    // -------------------------------------------------------------------------

    @PostMapping("/uid/{uid}/activate")
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.MANAGE')")
    public BomDto activate(@PathVariable String uid,
                           @Valid @RequestBody ActivateBomRequest request) {
        return bomService.activate(uid, request);
    }

    @PostMapping("/uid/{uid}/archive")
    @PreAuthorize("@perm.scoped(#uid,'bom','BOM.MANAGE')")
    public BomDto archive(@PathVariable String uid) {
        return bomService.archive(uid);
    }

    // -------------------------------------------------------------------------
    // Explosion
    // -------------------------------------------------------------------------

    /**
     * Multi-level explosion (FR-BOM-12/13, D-6).
     * ScopeGuard.assertCanActIn called within BomExplosionServiceImpl (read-path guard).
     */
    @GetMapping("/explode")
    @PreAuthorize("@perm.scoped(#parentProductUid,'product','BOM.VIEW')")
    public BomExplosionResultDto explode(
            @RequestParam(required = false) String parentProductUid,
            @RequestParam(required = false) String bomUid,
            @RequestParam(defaultValue = "1") BigDecimal outputQty,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "true") boolean multiLevel,
            @RequestParam(required = false) LocalDate asOfDate,
            @RequestParam(defaultValue = "false") boolean withCost) {
        return bomExplosionService.explode(
                parentProductUid, bomUid, outputQty, multiLevel, asOfDate, branchUid, withCost);
    }

    // -------------------------------------------------------------------------
    // Where-used (implosion)
    // -------------------------------------------------------------------------

    @GetMapping("/where-used/{componentProductUid}")
    @PreAuthorize("@perm.scoped(#componentProductUid,'product','BOM.VIEW')")
    public Object whereUsed(
            @PathVariable String componentProductUid,
            @RequestParam Long companyId,
            @RequestParam(defaultValue = "false") boolean full) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (full) {
            WhereUsedTreeDto tree = bomWhereUsedService.whereUsedTree(componentProductUid, companyId);
            return tree;
        }
        List<WhereUsedRowDto> rows = bomWhereUsedService.whereUsed(componentProductUid, companyId);
        return rows;
    }

    // -------------------------------------------------------------------------
    // Cost roll-up
    // -------------------------------------------------------------------------

    @GetMapping("/cost-roll-up")
    @PreAuthorize("@perm.scoped(#parentProductUid,'product','BOM.VIEW')")
    public BomCostRollUpDto costRollUp(
            @RequestParam(required = false) String parentProductUid,
            @RequestParam(required = false) String bomUid,
            @RequestParam String branchUid,
            @RequestParam(defaultValue = "1") BigDecimal outputQty) {
        return bomCostRollUpService.rollUp(parentProductUid, bomUid, branchUid, outputQty);
    }
}
