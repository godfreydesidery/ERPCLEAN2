package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.dto.CreateDimensionValueRequest;
import com.erp.modules.costing.domain.dto.DimensionDto;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.dto.SetDimensionMandatoryRequest;
import com.erp.modules.costing.domain.dto.UpdateDimensionValueRequest;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Master CRUD for dimension types and dimension values (ADR-0025 D-1).
 *
 * <p>Dimension types: list + set-mandatory (COSTING.MANAGE). Built-in dimensions cannot be deleted
 * (FR-CC-01). Dimension values: create / update / deactivate / delete (FR-CC-02/03/04).
 *
 * <p>resolveValue and listActiveValues are the documented Budgeting/Projects read contract (D-8).
 */
public interface DimensionService {

    // -------------------------------------------------------------------------
    // Dimension type operations (COSTING.VIEW / COSTING.MANAGE)
    // -------------------------------------------------------------------------

    List<DimensionDto> listDimensions(Long companyId);

    DimensionDto getDimensionByUid(String uid);

    /** Toggle mandatory/optional for the company (FR-CC-13, COSTING.MANAGE). */
    DimensionDto setMandatory(String uid, SetDimensionMandatoryRequest request);

    // -------------------------------------------------------------------------
    // Dimension value operations (COSTING.VIEW / COSTING.MANAGE)
    // -------------------------------------------------------------------------

    DimensionValueDto createValue(CreateDimensionValueRequest request);

    DimensionValueDto updateValue(String uid, UpdateDimensionValueRequest request);

    /** Deactivate a value — excludes from new tagging (FR-CC-04). */
    DimensionValueDto deactivateValue(String uid);

    /** Reactivate a previously deactivated value. */
    DimensionValueDto activateValue(String uid);

    /**
     * Hard-delete a value. Rejects if the value has any postings (FR-CC-03, BR-CC-05).
     * Use {@link #deactivateValue} when the value has postings.
     */
    void deleteValue(String uid);

    DimensionValueDto getValueByUid(String uid);

    Page<DimensionValueDto> listValues(String dimensionUid, Pageable pageable);

    // -------------------------------------------------------------------------
    // Budgeting / Projects read contract (D-8, FR-CC-17)
    // -------------------------------------------------------------------------

    /** Resolve a dimension value uid → DTO (id/company/slot) for Budgeting/Projects. */
    Optional<DimensionValueDto> resolveValue(String uid);

    /** List active values of a slot for a company — the picker source (FR-CC-15). */
    List<DimensionValueDto> listActiveValues(Long companyId, DimensionSlot slot);
}
