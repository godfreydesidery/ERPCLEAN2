package com.erp.modules.costing.service;

import com.erp.modules.costing.domain.dto.CreateDimensionValueRequest;
import com.erp.modules.costing.domain.dto.DimensionDto;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.dto.SetDimensionMandatoryRequest;
import com.erp.modules.costing.domain.dto.UpdateDimensionValueRequest;
import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.entity.DimensionValue;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Master CRUD for dimension types and values (ADR-0025 D-1/D-2, FR-CC-01..05/13).
 */
@Service
@Transactional
public class DimensionServiceImpl implements DimensionService {

    private final DimensionRepository       dimensions;
    private final DimensionValueRepository  values;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public DimensionServiceImpl(DimensionRepository dimensions,
                                 DimensionValueRepository values,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.dimensions = dimensions;
        this.values     = values;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    // -------------------------------------------------------------------------
    // Dimension type operations
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<DimensionDto> listDimensions(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return dimensions.findByCompanyId(companyId).stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public DimensionDto getDimensionByUid(String uid) {
        Dimension d = dimensions.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Dimension", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), d.getCompanyId());
        return toDto(d);
    }

    @Override
    public DimensionDto setMandatory(String uid, SetDimensionMandatoryRequest request) {
        Dimension d = dimensions.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Dimension", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), d.getCompanyId());

        boolean was = d.isMandatory();
        d.setMandatory(request.mandatory());
        d.setUpdatedAt(Instant.now());
        d.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.COSTING_DIMENSION_MANDATORY_SET,
                        "dimensions", d.getId(), d.getUid())
                .detail(Map.of("was", String.valueOf(was), "now", String.valueOf(request.mandatory()))));

        return toDto(d);
    }

    // -------------------------------------------------------------------------
    // Dimension value operations
    // -------------------------------------------------------------------------

    @Override
    public DimensionValueDto createValue(CreateDimensionValueRequest request) {
        if (request.dimensionUid() == null || request.dimensionUid().isBlank()) {
            throw new IllegalArgumentException("dimensionUid is required (must not be null or blank)");
        }
        Dimension dim = dimensions.findByUid(request.dimensionUid())
                .orElseThrow(() -> NotFoundException.of("Dimension", request.dimensionUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), dim.getCompanyId());

        Long parentId = resolveParentId(dim, request.parentUid());

        DimensionValue v = new DimensionValue(
                dim.getCompanyId(), dim.getId(),
                request.code(), request.name(),
                parentId, actorId());
        v = values.save(v);

        audit.record(AuditEvent.of(AuditActions.COSTING_VALUE_CREATE,
                        "dimension_values", v.getId(), v.getUid())
                .detail(Map.of("code", v.getCode(), "dimensionSlot", dim.getSlot().name())));

        return toValueDto(v, dim);
    }

    @Override
    public DimensionValueDto updateValue(String uid, UpdateDimensionValueRequest request) {
        DimensionValue v = values.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), v.getCompanyId());

        Dimension dim = dimensions.findById(v.getDimensionId())
                .orElseThrow(() -> NotFoundException.of("Dimension", v.getDimensionId().toString()));

        if (request.name() != null) {
            v.setName(request.name());
        }
        if (Boolean.TRUE.equals(request.clearParent())) {
            v.setParentId(null);
        } else if (request.parentUid() != null) {
            Long parentId = resolveParentId(dim, request.parentUid());
            assertNoCycle(v.getId(), parentId);
            v.setParentId(parentId);
        }
        v.setUpdatedAt(Instant.now());
        v.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.COSTING_VALUE_UPDATE,
                        "dimension_values", v.getId(), v.getUid())
                .detail(Map.of("name", v.getName())));

        return toValueDto(v, dim);
    }

    @Override
    public DimensionValueDto deactivateValue(String uid) {
        DimensionValue v = values.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), v.getCompanyId());

        v.setActive(false);
        v.setStatus(MasterStatus.INACTIVE);
        v.setUpdatedAt(Instant.now());
        v.setUpdatedBy(actorId());

        Dimension dim = dimensions.findById(v.getDimensionId()).orElse(null);
        audit.record(AuditEvent.of(AuditActions.COSTING_VALUE_DEACTIVATE,
                        "dimension_values", v.getId(), v.getUid())
                .detail(Map.of("code", v.getCode())));

        return toValueDto(v, dim);
    }

    @Override
    public DimensionValueDto activateValue(String uid) {
        DimensionValue v = values.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), v.getCompanyId());

        v.setActive(true);
        v.setStatus(MasterStatus.ACTIVE);
        v.setUpdatedAt(Instant.now());
        v.setUpdatedBy(actorId());

        Dimension dim = dimensions.findById(v.getDimensionId()).orElse(null);
        return toValueDto(v, dim);
    }

    @Override
    public void deleteValue(String uid) {
        DimensionValue v = values.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), v.getCompanyId());

        if (values.hasPostings(v.getId())) {
            throw new IllegalStateException(
                    "Dimension value " + uid + " is referenced by posted journal lines "
                            + "and cannot be hard-deleted (BR-CC-05). Use deactivate instead.");
        }
        values.delete(v);
    }

    @Override
    @Transactional(readOnly = true)
    public DimensionValueDto getValueByUid(String uid) {
        DimensionValue v = values.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), v.getCompanyId());
        Dimension dim = dimensions.findById(v.getDimensionId()).orElse(null);
        return toValueDto(v, dim);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DimensionValueDto> listValues(String dimensionUid, Pageable pageable) {
        if (dimensionUid == null || dimensionUid.isBlank()) {
            throw new IllegalArgumentException("dimensionUid is required (must not be null or blank)");
        }
        Dimension dim = dimensions.findByUid(dimensionUid)
                .orElseThrow(() -> NotFoundException.of("Dimension", dimensionUid));
        scopeGuard.assertCanActIn(RequestContext.get(), dim.getCompanyId());
        return values.findByDimensionId(dim.getId(), pageable)
                .map(v -> toValueDto(v, dim));
    }

    // -------------------------------------------------------------------------
    // Budgeting / Projects read contract
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<DimensionValueDto> resolveValue(String uid) {
        return values.findByUid(uid)
                .map(v -> {
                    Dimension dim = dimensions.findById(v.getDimensionId()).orElse(null);
                    return toValueDto(v, dim);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DimensionValueDto> resolveValueById(Long id) {
        if (id == null) return Optional.empty();
        return values.findById(id)
                .map(v -> {
                    Dimension dim = dimensions.findById(v.getDimensionId()).orElse(null);
                    return toValueDto(v, dim);
                });
    }

    @Override
    @Transactional(readOnly = true)
    public List<DimensionValueDto> listActiveValues(Long companyId, DimensionSlot slot) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Dimension dim = dimensions.findByCompanyIdAndSlot(companyId, slot).orElse(null);
        if (dim == null) {
            return List.of();
        }
        return values.findByDimensionIdAndActiveTrue(dim.getId()).stream()
                .map(v -> toValueDto(v, dim))
                .toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private Long resolveParentId(Dimension dim, String parentUid) {
        if (parentUid == null) {
            return null;
        }
        DimensionValue parent = values.findByCompanyIdAndUid(dim.getCompanyId(), parentUid)
                .orElseThrow(() -> NotFoundException.of("DimensionValue (parent)", parentUid));
        if (!parent.getDimensionId().equals(dim.getId())) {
            throw new IllegalArgumentException(
                    "Parent value " + parentUid + " belongs to a different dimension (BR-CC-09).");
        }
        return parent.getId();
    }

    /** BFS/DFS cycle detection: assert that parentId is not a descendant of valueId (BR-CC-09). */
    private void assertNoCycle(Long valueId, Long newParentId) {
        if (newParentId == null || newParentId.equals(valueId)) {
            if (newParentId != null && newParentId.equals(valueId)) {
                throw new IllegalArgumentException(
                        "A dimension value cannot be its own parent (BR-CC-09).");
            }
            return;
        }
        // Walk up from newParentId — if we reach valueId, it's a cycle
        Set<Long> visited = new HashSet<>();
        Long cursor = newParentId;
        while (cursor != null && !visited.contains(cursor)) {
            if (cursor.equals(valueId)) {
                throw new IllegalArgumentException(
                        "Setting parent would create a cycle in the dimension hierarchy (BR-CC-09).");
            }
            visited.add(cursor);
            Long finalCursor = cursor;
            cursor = values.findById(cursor).map(DimensionValue::getParentId).orElse(null);
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private DimensionDto toDto(Dimension d) {
        return new DimensionDto(
                d.getId(), d.getUid(), d.getCompanyId(),
                d.getSlot(), d.getCode(), d.getName(),
                d.isBuiltIn(), d.isMandatory(), d.getStatus());
    }

    private DimensionValueDto toValueDto(DimensionValue v, Dimension dim) {
        String dimUid  = dim != null ? dim.getUid() : null;
        String slot    = dim != null ? dim.getSlot().name() : null;
        String parentUid = null;
        if (v.getParentId() != null) {
            parentUid = values.findById(v.getParentId()).map(DimensionValue::getUid).orElse(null);
        }
        return new DimensionValueDto(
                v.getId(), v.getUid(), v.getCompanyId(),
                v.getDimensionId(), dimUid, slot,
                v.getCode(), v.getName(),
                v.getParentId(), parentUid,
                v.isActive(), v.getStatus());
    }
}
