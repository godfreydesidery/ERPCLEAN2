package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.AddBomComponentRequest;
import com.erp.modules.products.domain.dto.BomComponentDto;
import com.erp.modules.products.domain.dto.UpdateBomComponentRequest;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BOM component line management (ADR-0026 D-3, BR-BOM-01..03/07/10/12).
 *
 * <p>Guards enforced on every add:
 * <ol>
 *   <li>Header is DRAFT (BR-BOM-03 — ACTIVE is frozen).</li>
 *   <li>Component same-company (BR-BOM-10) + not-ARCHIVED (BR-BOM-12).</li>
 *   <li>No duplicate child in this version (BR-BOM-02 — DB-backed, service gives friendly error).</li>
 *   <li>Transitive cycle check via {@link BomCycleGuard} (BR-BOM-01).</li>
 * </ol>
 * Make/buy defaulting (BR-BOM-07): if {@code sourcing} is null, default MAKE when the child has an
 * ACTIVE BOM, else BUY.
 */
@Service
@Transactional
public class BomComponentServiceImpl implements BomComponentService {

    private final BomRepository boms;
    private final BomComponentRepository bomComponents;
    private final ProductRepository products;
    private final BomCycleGuard cycleGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public BomComponentServiceImpl(BomRepository boms,
                                    BomComponentRepository bomComponents,
                                    ProductRepository products,
                                    BomCycleGuard cycleGuard,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.boms = boms;
        this.bomComponents = bomComponents;
        this.products = products;
        this.cycleGuard = cycleGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public BomComponentDto add(String bomUid, AddBomComponentRequest req) {
        Bom bom = requireBom(bomUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        requireDraft(bom);

        // Resolve component product
        Product comp = Lookups.orNotFound(
                products.findByUid(req.componentProductUid()), "Product", req.componentProductUid());

        // BR-BOM-10: same company
        if (!comp.getCompanyId().equals(bom.getCompanyId())) {
            throw new com.erp.platform.common.api.ForbiddenException(
                    "The selected component product does not belong to the same company as this BOM.");
        }
        // BR-BOM-12: not archived
        if (MasterStatus.ARCHIVED.equals(comp.getStatus())) {
            // BR-BOM-12
            throw new IllegalArgumentException(
                    "The selected component product is archived and cannot be added to a BOM.");
        }

        // BR-BOM-02: no duplicate child
        if (bomComponents.findByBomIdAndComponentProductId(bom.getId(), comp.getId()).isPresent()) {
            // BR-BOM-02
            throw new ConflictException(
                    "This component is already in the current BOM version. Edit the existing line instead.");
        }

        // BR-BOM-01: transitive cycle check
        cycleGuard.assertNoCycle(bom.getParentProductId(), comp.getId());

        // BR-BOM-07: default make/buy from child's BOM state
        ComponentSourcing sourcing = req.sourcing();
        if (sourcing == null) {
            boolean childHasActiveBom = boms.existsByParentProductIdAndStatus(
                    comp.getId(), BomStatus.ACTIVE);
            sourcing = childHasActiveBom ? ComponentSourcing.MAKE : ComponentSourcing.BUY;
        }

        // Allocate next line_no
        short lineNo = (short) (bomComponents.maxLineNo(bom.getId()) + 10);

        BomComponent line = bomComponents.save(new BomComponent(
                bom.getId(),
                bom.getCompanyId(),
                lineNo,
                comp.getId(),
                comp.getCode(),
                comp.getName(),
                req.qtyPer(),
                sourcing,
                req.scrapPercent(),
                req.reference(),
                actorId()
        ));

        audit.record(AuditEvent.of(AuditActions.BOM_COMPONENT_ADD, "bom_components",
                        bom.getId(), bom.getUid())
                .detail(Map.of(
                        "componentUid", req.componentProductUid(),
                        "sourcing", sourcing.name(),
                        "qtyPer", req.qtyPer().toPlainString())));
        return BomComponentDto.from(line);
    }

    @Override
    public BomComponentDto update(String bomUid, String componentUid, UpdateBomComponentRequest req) {
        Bom bom = requireBom(bomUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        requireDraft(bom);

        BomComponent line = Lookups.orNotFound(
                bomComponents.findByUidAndBomId(componentUid, bom.getId()), "BomComponent", componentUid);

        if (req.qtyPer() != null) line.setQtyPer(req.qtyPer());
        if (req.sourcing() != null) line.setSourcing(req.sourcing());
        if (req.scrapPercent() != null) line.setScrapPercent(req.scrapPercent());
        if (req.reference() != null) line.setReference(req.reference());
        line.setUpdatedAt(Instant.now());
        line.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.BOM_COMPONENT_UPDATE, "bom_components",
                        bom.getId(), bom.getUid())
                .detail(Map.of("componentUid", componentUid)));
        return BomComponentDto.from(line);
    }

    @Override
    public void remove(String bomUid, String componentUid) {
        Bom bom = requireBom(bomUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        requireDraft(bom);

        BomComponent line = Lookups.orNotFound(
                bomComponents.findByUidAndBomId(componentUid, bom.getId()), "BomComponent", componentUid);
        bomComponents.delete(line);

        audit.record(AuditEvent.of(AuditActions.BOM_COMPONENT_REMOVE, "bom_components",
                        bom.getId(), bom.getUid())
                .detail(Map.of("componentUid", componentUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BomComponentDto> list(String bomUid) {
        Bom bom = requireBom(bomUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        return bomComponents.findByBomIdOrderByLineNo(bom.getId()).stream()
                .map(BomComponentDto::from)
                .toList();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Bom requireBom(String uid) {
        return Lookups.orNotFound(boms.findByUid(uid), "Bom", uid);
    }

    private static void requireDraft(Bom bom) {
        if (!BomStatus.DRAFT.equals(bom.getStatus())) {
            // BR-BOM-03: components frozen on non-DRAFT versions
            throw new IllegalStateException(
                    "BOM components can only be edited on a DRAFT version. " +
                    "Current status: " + bom.getStatus() +
                    ". Create a new DRAFT version to make changes.");
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
