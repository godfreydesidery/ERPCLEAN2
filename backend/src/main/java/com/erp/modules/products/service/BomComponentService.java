package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.AddBomComponentRequest;
import com.erp.modules.products.domain.dto.BomComponentDto;
import com.erp.modules.products.domain.dto.UpdateBomComponentRequest;
import java.util.List;

/**
 * Manages BOM component lines on DRAFT headers (ADR-0026 D-3, FR-BOM-07..11, BR-BOM-02..03/07..12).
 *
 * <p>All mutations require the header to be in DRAFT status (BR-BOM-03). Enforces:
 * same-company + not-archived guards (BR-BOM-10/12), duplicate-child guard (BR-BOM-02),
 * and the transitive cycle check (BR-BOM-01, via {@link BomCycleGuard}).
 */
public interface BomComponentService {

    /**
     * Add a component line to a DRAFT BOM header (BR-BOM-03/07/10/12 guards applied).
     * Make/buy defaulted from child BOM state if not supplied (BR-BOM-07).
     *
     * @param bomUid the DRAFT BOM header uid
     * @param req    the component to add
     * @return the persisted component DTO
     */
    BomComponentDto add(String bomUid, AddBomComponentRequest req);

    /**
     * Update a component line on a DRAFT BOM header (BR-BOM-03 — ACTIVE is frozen).
     *
     * @param bomUid        the DRAFT BOM header uid
     * @param componentUid  the component line uid
     * @param req           fields to update (null = no change)
     * @return the updated component DTO
     */
    BomComponentDto update(String bomUid, String componentUid, UpdateBomComponentRequest req);

    /**
     * Remove a component line from a DRAFT BOM header.
     *
     * @param bomUid       the DRAFT BOM header uid
     * @param componentUid the component line uid to remove
     */
    void remove(String bomUid, String componentUid);

    /**
     * List all component lines for a BOM header (read-only; allowed on any status).
     *
     * @param bomUid the BOM header uid
     * @return ordered list of component DTOs
     */
    List<BomComponentDto> list(String bomUid);
}
