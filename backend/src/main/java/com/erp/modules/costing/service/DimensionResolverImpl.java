package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.dto.DimensionTagDto;
import com.erp.modules.costing.domain.dto.ResolvedDimensionTagDto;
import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.entity.DimensionValue;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.platform.common.api.NotFoundException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolution and validation authority for dimension tags on the posting path (ADR-0025 D-5).
 *
 * <p>Posters call {@link #resolveTag} before building a {@code LineDraft}.
 * {@code GLPostingService.post} calls {@link #validateForPosting} for each non-null id
 * (re-assertion via table-level read — D-7 cross-module table read, not a service cycle).
 */
@Service
@Transactional(readOnly = true)
public class DimensionResolverImpl implements DimensionResolver {

    private final DimensionRepository      dimensionRepo;
    private final DimensionValueRepository valueRepo;

    public DimensionResolverImpl(DimensionRepository dimensionRepo,
                                  DimensionValueRepository valueRepo) {
        this.dimensionRepo = dimensionRepo;
        this.valueRepo     = valueRepo;
    }

    @Override
    public ResolvedDimensionTagDto resolveTag(Long companyId, DimensionTagDto tag) {
        if (tag == null) {
            return ResolvedDimensionTagDto.empty();
        }
        Long ccId   = resolveSlot(companyId, DimensionSlot.COST_CENTRE, tag.costCentreValueUid());
        Long deptId = resolveSlot(companyId, DimensionSlot.DEPARTMENT,  tag.departmentValueUid());
        Long d3Id   = resolveSlot(companyId, DimensionSlot.DIMENSION_3,  tag.dimension3ValueUid());
        Long d4Id   = resolveSlot(companyId, DimensionSlot.DIMENSION_4,  tag.dimension4ValueUid());
        return new ResolvedDimensionTagDto(ccId, deptId, d3Id, d4Id);
    }

    @Override
    public void validateForPosting(Long companyId, DimensionSlot slot, Long valueId) {
        if (valueId == null) {
            return; // untagged — always valid (BR-CC-03)
        }
        DimensionValue v = valueRepo.findByIdAndCompanyId(valueId, companyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dimension value id=" + valueId + " not found in company " + companyId
                                + " (BR-CC-04 — cross-company or unknown value rejected)."));

        if (!v.isActive()) {
            throw new IllegalArgumentException(
                    "Dimension value " + v.getUid() + " ('" + v.getCode()
                            + "') is inactive; cannot tag a posting with it (BR-CC-04 / FR-CC-07).");
        }

        // Assert the value belongs to the dimension occupying the expected slot
        Dimension dim = dimensionRepo.findById(v.getDimensionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dimension not found for value id=" + valueId));
        if (dim.getSlot() != slot) {
            throw new IllegalArgumentException(
                    "Dimension value " + v.getUid() + " belongs to slot " + dim.getSlot()
                            + " but was supplied in slot " + slot
                            + " (BR-CC-04 — wrong-slot value rejected).");
        }
    }

    @Override
    public Set<DimensionSlot> mandatorySlots(Long companyId) {
        List<DimensionSlot> mandatory = dimensionRepo.findMandatorySlots(companyId);
        return mandatory.isEmpty() ? Set.of() : EnumSet.copyOf(mandatory);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Resolve a uid for a slot, asserting active + correct slot + same company. Returns null if uid is null. */
    private Long resolveSlot(Long companyId, DimensionSlot slot, String uid) {
        if (uid == null) {
            return null;
        }
        DimensionValue v = valueRepo.findByCompanyIdAndUid(companyId, uid)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dimension value uid='" + uid + "' not found in company " + companyId
                                + " (BR-CC-04 — cross-company or unknown value rejected)."));

        if (!v.isActive()) {
            throw new IllegalArgumentException(
                    "Dimension value '" + uid + "' (code='" + v.getCode()
                            + "') is inactive; cannot tag a posting with it (FR-CC-04 / BR-CC-04).");
        }

        // Verify the value's dimension occupies the expected slot
        Dimension dim = dimensionRepo.findById(v.getDimensionId())
                .orElseThrow(() -> NotFoundException.of("Dimension", v.getDimensionId().toString()));
        if (dim.getSlot() != slot) {
            throw new IllegalArgumentException(
                    "Dimension value '" + uid + "' belongs to slot " + dim.getSlot()
                            + " but was supplied for slot " + slot
                            + " (BR-CC-04 — wrong-slot value rejected).");
        }
        return v.getId();
    }
}
