package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ActivateBomRequest;
import com.erp.modules.products.domain.dto.AddBomComponentRequest;
import com.erp.modules.products.domain.dto.BomComponentDto;
import com.erp.modules.products.domain.dto.BomDto;
import com.erp.modules.products.domain.dto.CreateBomRequest;
import com.erp.modules.products.domain.dto.UpdateBomRequest;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductComponent;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductComponentRepository;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BOM header lifecycle (ADR-0026 D-2, FR-BOM-01..06, BR-BOM-03/04/05).
 *
 * <p>Activation is the critical flow: validates the structure, then in one transaction:
 * supersedes the prior ACTIVE version (closes its effectivity window, sets ARCHIVED),
 * and activates this DRAFT version. The {@code uq_bom_one_active} partial-unique index
 * is the race-safe backstop (D-2).
 *
 * <p>Promote-recipe-to-BOM (OQ-BOM-01, D-5): copies product_components rows into a new DRAFT
 * BOM v1 with make/buy defaulted, leaving the recipe rows intact.
 */
@Service
@Transactional
public class BomServiceImpl implements BomService {

    private final BomRepository boms;
    private final BomComponentRepository bomComponents;
    private final ProductRepository products;
    private final ProductComponentRepository legacyComponents;
    private final BomCycleGuard cycleGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public BomServiceImpl(BomRepository boms,
                           BomComponentRepository bomComponents,
                           ProductRepository products,
                           ProductComponentRepository legacyComponents,
                           BomCycleGuard cycleGuard,
                           ScopeGuard scopeGuard,
                           AuditService audit) {
        this.boms = boms;
        this.bomComponents = bomComponents;
        this.products = products;
        this.legacyComponents = legacyComponents;
        this.cycleGuard = cycleGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    @Override
    public BomDto create(Long companyId, CreateBomRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Product parent = Lookups.orNotFound(
                products.findByUid(req.parentProductUid()), "Product", req.parentProductUid());
        if (!parent.getCompanyId().equals(companyId)) {
            throw new com.erp.platform.common.api.ForbiddenException(
                    "Parent product does not belong to the specified company.");
        }
        if (MasterStatus.ARCHIVED.equals(parent.getStatus())) {
            // BR-BOM-12: archived parent
            throw new IllegalArgumentException(
                    "A bill of materials cannot be created for an archived product.");
        }

        // Allocate version_no (uq_bom_parent_version is the race backstop)
        int nextVersion = boms.maxVersionNo(parent.getId()) + 1;

        BigDecimal outputQty = req.outputQty() != null ? req.outputQty() : BigDecimal.ONE;
        BigDecimal yieldPct = req.yieldPercent() != null ? req.yieldPercent() : BigDecimal.valueOf(100);

        String sourceBomUid = req.cloneFromBomUid();
        Bom bom = boms.save(new Bom(companyId, parent.getId(), nextVersion,
                outputQty, yieldPct, req.notes(), sourceBomUid, actorId()));

        // Clone components if requested (OQ-BOM-06)
        if (sourceBomUid != null) {
            cloneComponents(sourceBomUid, bom);
        }

        audit.record(AuditEvent.of(AuditActions.BOM_CREATE, "boms", bom.getId(), bom.getUid())
                .detail(Map.of("parentProductUid", req.parentProductUid(),
                        "versionNo", String.valueOf(nextVersion))));
        return toBomDto(bom);
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public BomDto getByUid(String uid) {
        Bom bom = requireBom(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        return toBomDtoWithComponents(bom);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BomDto> list(Long companyId, String parentProductUid, BomStatus status,
                              Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return boms.findByFilter(companyId, parentProductUid, status, pageable)
                .map(b -> toBomDto(b));
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    @Override
    public BomDto update(String uid, UpdateBomRequest req) {
        Bom bom = requireBom(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());

        if (BomStatus.ARCHIVED.equals(bom.getStatus())) {
            throw new IllegalStateException("Cannot update an ARCHIVED BOM.");
        }

        // Notes editable on ACTIVE (OQ-BOM-03); structural fields only on DRAFT
        if (BomStatus.DRAFT.equals(bom.getStatus())) {
            if (req.outputQty() != null) bom.setOutputQty(req.outputQty());
            if (req.yieldPercent() != null) bom.setYieldPercent(req.yieldPercent());
        }
        if (req.notes() != null) bom.setNotes(req.notes());
        bom.setUpdatedAt(Instant.now());
        bom.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.BOM_UPDATE, "boms", bom.getId(), bom.getUid()));
        return toBomDto(bom);
    }

    // -------------------------------------------------------------------------
    // Activate
    // -------------------------------------------------------------------------

    @Override
    public BomDto activate(String uid, ActivateBomRequest req) {
        Bom bom = requireBom(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());

        if (!BomStatus.DRAFT.equals(bom.getStatus())) {
            throw new IllegalStateException(
                    "Only a DRAFT BOM can be activated. Current status: " + bom.getStatus());
        }

        // Validate: ≥1 component
        List<?> lines = bomComponents.findByBomIdOrderByLineNo(bom.getId());
        if (lines.isEmpty()) {
            throw new IllegalStateException(
                    "Cannot activate a BOM with no components. Add at least one component first.");
        }

        // Validate: full-tree cycle check (belt-and-suspenders at activate)
        lines.forEach(raw -> {
            var line = (com.erp.modules.products.domain.entity.BomComponent) raw;
            cycleGuard.assertNoCycle(bom.getParentProductId(), line.getComponentProductId());
        });

        // Supersede prior ACTIVE version (atomic, in same TX — BR-BOM-04/05).
        // saveAndFlush the ARCHIVED state BEFORE setting the new BOM ACTIVE — this is the
        // Hibernate autoflush-ordering trap: without an explicit flush here, both dirty rows
        // (prior→ARCHIVED, new→ACTIVE) may be written in registration order and the partial-unique
        // index uq_bom_one_active fires because the new ACTIVE row lands before the old one is
        // de-activated at the DB level.
        Optional<Bom> priorActive = boms.findByParentProductIdAndStatus(
                bom.getParentProductId(), BomStatus.ACTIVE);
        priorActive.ifPresent(prior -> {
            prior.setStatus(BomStatus.ARCHIVED);
            prior.setEffectiveTo(req.effectiveFrom());
            prior.setArchivedAt(Instant.now());
            prior.setUpdatedAt(Instant.now());
            prior.setUpdatedBy(actorId());
            boms.saveAndFlush(prior); // flush ARCHIVED to DB before new ACTIVE is written
            audit.record(AuditEvent.of(AuditActions.BOM_SUPERSEDE, "boms",
                            prior.getId(), prior.getUid())
                    .detail(Map.of("supersededBy", uid,
                            "effectiveTo", req.effectiveFrom().toString())));
        });

        // Activate this version
        bom.setStatus(BomStatus.ACTIVE);
        bom.setEffectiveFrom(req.effectiveFrom());
        bom.setEffectiveTo(null); // open-ended
        bom.setActivatedAt(Instant.now());
        bom.setUpdatedAt(Instant.now());
        bom.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.BOM_ACTIVATE, "boms", bom.getId(), bom.getUid())
                .detail(Map.of("effectiveFrom", req.effectiveFrom().toString(),
                        "versionNo", String.valueOf(bom.getVersionNo()))));
        return toBomDtoWithComponents(bom);
    }

    // -------------------------------------------------------------------------
    // Archive
    // -------------------------------------------------------------------------

    @Override
    public BomDto archive(String uid) {
        Bom bom = requireBom(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());

        if (BomStatus.ARCHIVED.equals(bom.getStatus())) {
            throw new IllegalStateException("BOM is already ARCHIVED.");
        }

        bom.setStatus(BomStatus.ARCHIVED);
        bom.setArchivedAt(Instant.now());
        bom.setUpdatedAt(Instant.now());
        bom.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.BOM_ARCHIVE, "boms", bom.getId(), bom.getUid()));
        return toBomDto(bom);
    }

    // -------------------------------------------------------------------------
    // Promote legacy recipe to BOM (OQ-BOM-01, D-5)
    // -------------------------------------------------------------------------

    @Override
    public BomDto promoteRecipeToDraftBom(String parentProductUid, Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Product parent = Lookups.orNotFound(
                products.findByUid(parentProductUid), "Product", parentProductUid);
        if (!parent.getCompanyId().equals(companyId)) {
            throw new com.erp.platform.common.api.ForbiddenException(
                    "Product does not belong to the specified company.");
        }

        List<ProductComponent> legacyLines = legacyComponents.findByComposedProductId(parent.getId());
        if (legacyLines.isEmpty()) {
            // OQ-BOM-01: no legacy recipe rows to promote
            throw new NotFoundException(
                    "This product has no existing recipe to promote into a bill of materials.");
        }

        int nextVersion = boms.maxVersionNo(parent.getId()) + 1;
        Bom bom = boms.save(new Bom(companyId, parent.getId(), nextVersion,
                BigDecimal.ONE, BigDecimal.valueOf(100),
                "Promoted from legacy recipe", null, actorId()));

        // Copy recipe rows as BOM components with make/buy defaulted (BR-BOM-07)
        short lineNo = 10;
        for (ProductComponent pc : legacyLines) {
            Product comp = pc.getComponentProduct();
            boolean childHasActiveBom = boms.existsByParentProductIdAndStatus(
                    comp.getId(), BomStatus.ACTIVE);
            ComponentSourcing sourcing = childHasActiveBom ? ComponentSourcing.MAKE : ComponentSourcing.BUY;
            bomComponents.save(new com.erp.modules.products.domain.entity.BomComponent(
                    bom.getId(), companyId, lineNo,
                    comp.getId(), comp.getCode(), comp.getName(),
                    pc.getQuantity(), sourcing, BigDecimal.ZERO, null, actorId()
            ));
            lineNo += 10;
        }

        audit.record(AuditEvent.of(AuditActions.BOM_PROMOTE_RECIPE, "boms", bom.getId(), bom.getUid())
                .detail(Map.of("parentProductUid", parentProductUid,
                        "legacyLines", String.valueOf(legacyLines.size()))));
        return toBomDtoWithComponents(bom);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Bom requireBom(String uid) {
        return Lookups.orNotFound(boms.findByUid(uid), "Bom", uid);
    }

    private void cloneComponents(String sourceBomUid, Bom target) {
        Bom source = Lookups.orNotFound(boms.findByUid(sourceBomUid), "Bom (clone source)", sourceBomUid);
        // Security: a cross-tenant clone would copy another company's recipe into the caller's
        // tenant under the guise of a clone.  404 (not 403) — mirrors the require-scoped pattern
        // used throughout this module and avoids confirming that a foreign BOM uid exists.
        if (!source.getCompanyId().equals(target.getCompanyId())) {
            throw new NotFoundException("BOM not found.");
        }
        List<com.erp.modules.products.domain.entity.BomComponent> sourceLines =
                bomComponents.findByBomIdOrderByLineNo(source.getId());
        for (com.erp.modules.products.domain.entity.BomComponent src : sourceLines) {
            bomComponents.save(new com.erp.modules.products.domain.entity.BomComponent(
                    target.getId(), target.getCompanyId(), src.getLineNo(),
                    src.getComponentProductId(), src.getComponentProductCode(), src.getComponentProductName(),
                    src.getQtyPer(), src.getSourcing(), src.getScrapPercent(), src.getReference(), actorId()
            ));
        }
    }

    private BomDto toBomDto(Bom b) {
        String parentUid = products.findById(b.getParentProductId())
                .map(Product::getUid).orElse(null);
        return BomDto.headerOnly(b, parentUid);
    }

    private BomDto toBomDtoWithComponents(Bom b) {
        String parentUid = products.findById(b.getParentProductId())
                .map(Product::getUid).orElse(null);
        List<BomComponentDto> comps = bomComponents.findByBomIdOrderByLineNo(b.getId()).stream()
                .map(BomComponentDto::from).toList();
        return BomDto.from(b, parentUid, comps);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
