package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ActivateBomRequest;
import com.erp.modules.products.domain.dto.BomDto;
import com.erp.modules.products.domain.dto.CreateBomRequest;
import com.erp.modules.products.domain.dto.UpdateBomRequest;
import com.erp.modules.products.domain.enums.BomStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * BOM header lifecycle service (ADR-0026 D-2, FR-BOM-01..06, BR-BOM-03/04/05).
 *
 * <p>Manages DRAFT creation, metadata updates, activation (with atomic supersede of the prior
 * ACTIVE version), archive, and the convenience promote-recipe-to-BOM (OQ-BOM-01 / D-5).
 */
public interface BomService {

    /**
     * Create a new DRAFT BOM header for the given parent product.
     * If {@code req.cloneFromBomUid()} is provided, copy the source version's component lines
     * into the new DRAFT (OQ-BOM-06).
     *
     * @param companyId the tenant (from JWT/ScopeGuard)
     * @param req       the creation request
     * @return the newly created DRAFT BOM header (without component list)
     */
    BomDto create(Long companyId, CreateBomRequest req);

    /**
     * Retrieve a BOM header by uid, with its component list.
     *
     * @param uid the BOM uid
     * @return the BOM DTO with components
     */
    BomDto getByUid(String uid);

    /**
     * Paged list of BOM headers, optionally filtered by parent product uid and/or status.
     *
     * @param companyId        tenant scope (required)
     * @param parentProductUid optional filter
     * @param status           optional filter
     * @param pageable         pagination
     * @return page of BOM header DTOs (header-only, no component list)
     */
    Page<BomDto> list(Long companyId, String parentProductUid, BomStatus status, Pageable pageable);

    /**
     * Update a BOM header's metadata.
     * On DRAFT: outputQty + yieldPercent + notes editable.
     * On ACTIVE: only notes editable (BR-BOM-03 component-set freeze).
     *
     * @param uid the BOM uid
     * @param req the update request
     * @return updated BOM DTO
     */
    BomDto update(String uid, UpdateBomRequest req);

    /**
     * Activate a DRAFT BOM, atomically superseding the prior ACTIVE version of the same parent
     * (BR-BOM-03/04/05, D-2).
     *
     * <p>Validates: ≥1 component, acyclic (BomCycleGuard full-tree pass), non-overlapping effectivity.
     * Supersede: the prior ACTIVE version's {@code effective_to} is set to {@code req.effectiveFrom()},
     * its status becomes ARCHIVED.
     *
     * @param uid the DRAFT BOM uid
     * @param req the activate request (effectiveFrom date)
     * @return the now-ACTIVE BOM DTO
     */
    BomDto activate(String uid, ActivateBomRequest req);

    /**
     * Archive a BOM header (decommission without a successor).
     * Works on DRAFT (deletes) or ACTIVE (archives — no parent then has an ACTIVE BOM).
     *
     * @param uid the BOM uid
     * @return the archived BOM DTO
     */
    BomDto archive(String uid);

    /**
     * Convenience: promote the parent product's v1 single-level {@code product_components} recipe
     * to a new DRAFT BOM version 1, copying the recipe rows as BOM components with make/buy
     * defaulted (OQ-BOM-01, D-5). The recipe rows are left intact.
     *
     * @param parentProductUid the product whose recipe to promote
     * @param companyId        tenant scope
     * @return the new DRAFT BOM DTO
     */
    BomDto promoteRecipeToDraftBom(String parentProductUid, Long companyId);
}
