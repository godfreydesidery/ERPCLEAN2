package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.dto.DimensionTagDto;
import com.erp.modules.costing.domain.dto.ResolvedDimensionTagDto;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import java.util.Set;

/**
 * Validation/resolution authority the posting path calls before building a {@code LineDraft}
 * (ADR-0025 D-5). Lives in the {@code costing} module; {@code gl/sales/purchases/stock} posters
 * call this before building their draft — the resolved Long ids are then passed onto the
 * {@code LineDraft} extension (D-4).
 *
 * <p>No cross-module service cycle: the poster calls {@code resolveTag} here (poster → costing),
 * GL re-asserts via a narrow table-level read projection (D-7). GL does not import this interface.
 */
public interface DimensionResolver {

    /**
     * Resolve a document's uid-based dimension tag to validated Long ids (ADR-0025 D-5).
     * Each non-null uid is validated: active, correct slot, same company. Throws
     * {@link IllegalArgumentException} on any violation (BR-CC-04 — rejected). A null/empty
     * tag returns {@link ResolvedDimensionTagDto#empty()} (all null — untagged draft).
     *
     * @param companyId the posting company (tenant scope)
     * @param tag       the uid-based tag declared on the document (all fields nullable)
     * @return resolved Long ids (nullable per slot)
     * @throws IllegalArgumentException if any uid is inactive / wrong slot / cross-company
     */
    ResolvedDimensionTagDto resolveTag(Long companyId, DimensionTagDto tag);

    /**
     * Per-id validity assertion reused by the GL posting engine's step 2 (ADR-0025 D-4).
     * Asserts: value is active, belongs to the dimension occupying {@code slot}, lives in
     * {@code companyId}. Throws {@link IllegalArgumentException} on any violation.
     *
     * <p>When {@code valueId} is null the assertion is a no-op (untagged — always valid).
     */
    void validateForPosting(Long companyId, DimensionSlot slot, Long valueId);

    /**
     * Returns the slots whose dimension has {@code is_mandatory=true} for this company (D-4 step 3).
     * Empty when no dimension is mandatory — the default for v1, so this check is a no-op for an
     * un-configured company (NFR-CC-01).
     */
    Set<DimensionSlot> mandatorySlots(Long companyId);
}
