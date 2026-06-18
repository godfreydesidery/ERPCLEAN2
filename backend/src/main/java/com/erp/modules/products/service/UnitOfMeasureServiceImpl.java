package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UpdateUnitOfMeasureRequest;
import com.erp.modules.products.domain.entity.UnitOfMeasure;
import com.erp.modules.products.domain.enums.DimensionType;
import com.erp.modules.products.repository.UnitOfMeasureRepository;
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
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Unit of Measure master administration (brief §UoM master).
 * Mirrors {@link PriceListServiceImpl}: assertCanActIn on every read path;
 * resolve companyUid→id + assertCanActIn on create.
 */
@Service
@Transactional
public class UnitOfMeasureServiceImpl implements UnitOfMeasureService {

    private final UnitOfMeasureRepository units;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public UnitOfMeasureServiceImpl(UnitOfMeasureRepository units,
                                    CompanyRepository companies,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.units = units;
        this.companies = companies;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public UnitOfMeasureDto create(CreateUnitOfMeasureRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        if (units.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ConflictException(
                    "Unit code '" + req.code() + "' already exists for this company.");
        }

        UnitOfMeasure u = new UnitOfMeasure(companyId, req.code(), req.name(), actorId());
        applyMetadata(u, req.symbol(), req.dimensionType(), req.decimalPlaces(), req.fractional());
        UnitOfMeasure saved = units.save(u);
        audit.record(AuditEvent.of(AuditActions.UOM_CREATE, "units_of_measure",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(), "name", saved.getName())));
        return UnitOfMeasureDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public UnitOfMeasureDto getByUid(String uid) {
        UnitOfMeasure u = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), u.getCompanyId());
        return UnitOfMeasureDto.from(u);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<UnitOfMeasureDto> list(Long companyId, String q, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return units.search(companyId, q, pageable).map(UnitOfMeasureDto::from);
        }
        return units.findByCompanyId(companyId, pageable).map(UnitOfMeasureDto::from);
    }

    @Override
    public UnitOfMeasureDto updateByUid(String uid, UpdateUnitOfMeasureRequest req) {
        UnitOfMeasure u = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), u.getCompanyId());
        u.setName(req.name());
        applyMetadata(u, req.symbol(), req.dimensionType(), req.decimalPlaces(), req.fractional());
        u.setUpdatedAt(Instant.now());
        u.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.UOM_UPDATE, "units_of_measure",
                u.getId(), u.getUid()));
        return UnitOfMeasureDto.from(u);
    }

    @Override
    public void archiveByUid(String uid) {
        UnitOfMeasure u = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), u.getCompanyId());
        MasterStatus prev = u.getStatus();
        u.setStatus(MasterStatus.ARCHIVED);
        u.setUpdatedAt(Instant.now());
        u.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.UOM_ARCHIVE, "units_of_measure",
                        u.getId(), u.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        UnitOfMeasure u = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), u.getCompanyId());
        MasterStatus prev = u.getStatus();
        u.setStatus(MasterStatus.ACTIVE);
        u.setUpdatedAt(Instant.now());
        u.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.UOM_RESTORE, "units_of_measure",
                        u.getId(), u.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private UnitOfMeasure require(String uid) {
        return Lookups.orNotFound(units.findByUid(uid), "UnitOfMeasure", uid);
    }

    /**
     * Applies the optional P2 D5 unit metadata. {@code symbol} is set as-is (null = clear). The
     * primitive {@code decimalPlaces}/{@code fractional} and the defaulted {@code dimensionType} are
     * only overwritten when the request supplied a value (null = keep the entity default).
     */
    private static void applyMetadata(UnitOfMeasure u, String symbol, DimensionType dimensionType,
                                      Short decimalPlaces, Boolean fractional) {
        u.setSymbol(symbol);
        if (dimensionType != null) {
            u.setDimensionType(dimensionType);
        }
        if (decimalPlaces != null) {
            u.setDecimalPlaces(decimalPlaces);
        }
        if (fractional != null) {
            u.setFractional(fractional);
        }
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyUid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
