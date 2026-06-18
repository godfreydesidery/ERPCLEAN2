package com.erp.modules.fixedassets.service;

import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.dto.SupplierBillLineDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.service.SupplierBillService;
import com.erp.modules.fixedassets.domain.dto.AcquireFromBillRequest;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.PlaceInServiceRequest;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.TransferAssetRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetRequest;
import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FixedAssetServiceImpl implements FixedAssetService {

    private final FixedAssetRepository          assets;
    private final AssetCategoryRepository       categories;
    private final FixedAssetNumberGenerator     numberGenerator;
    private final DepreciationScheduleService   scheduleService;
    private final FixedAssetGlPoster            glPoster;
    private final SupplierBillService           billService;
    private final ScopeGuard                    scopeGuard;
    private final AuditService                  audit;

    public FixedAssetServiceImpl(FixedAssetRepository assets,
                                  AssetCategoryRepository categories,
                                  FixedAssetNumberGenerator numberGenerator,
                                  DepreciationScheduleService scheduleService,
                                  FixedAssetGlPoster glPoster,
                                  SupplierBillService billService,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.assets          = assets;
        this.categories      = categories;
        this.numberGenerator = numberGenerator;
        this.scheduleService = scheduleService;
        this.glPoster        = glPoster;
        this.billService     = billService;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    @Override
    public FixedAssetDto register(RegisterAssetRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        requireCategory(req.companyId(), req.categoryId());

        String assetNumber = numberGenerator.nextAssetNumber(req.companyId());
        BigDecimal salvage = req.salvageValue() != null ? req.salvageValue() : BigDecimal.ZERO;

        FixedAsset asset = new FixedAsset(
                req.companyId(), req.branchId(), assetNumber, req.categoryId(),
                req.name(), req.acquisitionCost(), salvage,
                req.depreciationMethod(), req.lifePeriods(), req.reducingRate(),
                req.acquisitionDate(), req.depreciationStartDate(), actorId());
        asset.setLocation(req.location());
        asset.setCostCentreId(req.costCentreId());
        asset.setAssetTag(req.assetTag());
        asset = assets.save(asset);

        audit.record(AuditEvent.of(AuditActions.FA_ASSET_REGISTER, "fixed_assets",
                        asset.getId(), asset.getUid())
                .detail(Map.of("assetNumber", asset.getAssetNumber(), "name", asset.getName())));

        return toDto(asset);
    }

    @Override
    public FixedAssetDto acquireFromBill(AcquireFromBillRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        requireCategory(req.companyId(), req.categoryId());

        // Read the AP bill DTO — no AP entity import (D-7, D-12)
        SupplierBillDto bill = billService.getByUid(req.billUid());
        if (bill.companyId() == null || !bill.companyId().equals(req.companyId())) {
            throw new IllegalArgumentException("Bill does not belong to the specified company.");
        }
        if (bill.status() != SupplierBillStatus.MATCHED) {
            throw new IllegalArgumentException(
                    "Bill must be MATCHED to capitalise an asset from it (ADR-0030 D-7).");
        }

        SupplierBillLineDto line = bill.lines().stream()
                .filter(l -> l.uid().equals(req.billLineUid()))
                .findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "Bill line '" + req.billLineUid() + "' not found on bill '" + req.billUid() + "'."));

        BigDecimal acquisitionCost = line.lineNetAmount(); // net, VAT excluded (BR-FA-07)
        BigDecimal salvage = req.salvageValue() != null ? req.salvageValue() : BigDecimal.ZERO;

        String assetNumber = numberGenerator.nextAssetNumber(req.companyId());

        FixedAsset asset = new FixedAsset(
                req.companyId(), req.branchId(), assetNumber, req.categoryId(),
                req.name(), acquisitionCost, salvage,
                req.depreciationMethod(), req.lifePeriods(), req.reducingRate(),
                req.acquisitionDate(), req.depreciationStartDate(), actorId());
        asset.setLocation(req.location());
        asset.setCostCentreId(req.costCentreId());
        asset.setAssetTag(req.assetTag());
        asset.setSupplierId(bill.supplierId());
        asset.setSourceBillUid(bill.uid());
        asset = assets.save(asset);

        // Place in service immediately (same TX)
        asset = placeInServiceInternal(asset, req.acquisitionDate());

        audit.record(AuditEvent.of(AuditActions.FA_ASSET_IN_SERVICE, "fixed_assets",
                        asset.getId(), asset.getUid())
                .detail(Map.of("assetNumber", asset.getAssetNumber(),
                        "billUid", req.billUid(),
                        "acquisitionCost", acquisitionCost.toPlainString())));

        return toDto(asset);
    }

    @Override
    @Transactional(readOnly = true)
    public FixedAssetDto getByUid(String uid) {
        FixedAsset asset = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());
        return toDto(asset);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FixedAssetDto> listByCompany(Long companyId, FixedAssetStatus status, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (status != null) {
            return assets.findByCompanyIdAndStatus(companyId, status, pageable).map(this::toDto);
        }
        return assets.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public FixedAssetDto update(String uid, UpdateAssetRequest req) {
        FixedAsset asset = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());

        if (asset.getStatus() != FixedAssetStatus.DRAFT) {
            throw new IllegalArgumentException(
                    "Non-financial fields can only be updated while DRAFT (BR-FA-09).");
        }

        asset.setName(req.name());
        asset.setLocation(req.location());
        asset.setAssetTag(req.assetTag());
        asset.setCostCentreId(req.costCentreId());
        asset.setUpdatedAt(Instant.now());
        asset.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.FA_ASSET_UPDATE, "fixed_assets",
                        asset.getId(), asset.getUid())
                .detail(Map.of("name", req.name())));

        return toDto(asset);
    }

    @Override
    public FixedAssetDto placeInService(String uid, PlaceInServiceRequest req) {
        FixedAsset asset = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());

        if (asset.getStatus() != FixedAssetStatus.DRAFT) {
            throw new IllegalArgumentException("Asset must be DRAFT to place in service.");
        }

        asset = placeInServiceInternal(asset, req.postingDate());

        audit.record(AuditEvent.of(AuditActions.FA_ASSET_IN_SERVICE, "fixed_assets",
                        asset.getId(), asset.getUid())
                .detail(Map.of("assetNumber", asset.getAssetNumber(),
                        "postingDate", req.postingDate().toString())));

        return toDto(asset);
    }

    @Override
    public FixedAssetDto transfer(String uid, TransferAssetRequest req) {
        FixedAsset asset = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), asset.getCompanyId());

        if (asset.getStatus() == FixedAssetStatus.DISPOSED
                || asset.getStatus() == FixedAssetStatus.WRITTEN_OFF) {
            throw new IllegalArgumentException("Cannot transfer a disposed or written-off asset.");
        }

        if (req.branchId() != null) asset.setBranchId(req.branchId());
        if (req.location() != null) asset.setLocation(req.location());
        if (req.costCentreId() != null) asset.setCostCentreId(req.costCentreId());
        asset.setUpdatedAt(Instant.now());
        asset.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.FA_ASSET_TRANSFER, "fixed_assets",
                        asset.getId(), asset.getUid())
                .detail(Map.of("assetNumber", asset.getAssetNumber())));

        return toDto(asset);
    }

    // -------------------------------------------------------------------------

    private FixedAsset placeInServiceInternal(FixedAsset asset, java.time.LocalDate postingDate) {
        AssetCategory category = requireCategory(asset.getCompanyId(), asset.getCategoryId());

        // Determine currency — use "TZS" as default (company base currency; a proper currency
        // resolution from the company record would be added when multi-currency is wired)
        String currency = "TZS";

        // Generate the depreciation schedule (D-5)
        scheduleService.generate(asset, actorId());

        // Post the capitalisation journal (D-6a, D-7)
        JournalEntryDto journal = glPoster.postCapitalisation(
                asset, category, postingDate, currency, actorId());

        asset.setStatus(FixedAssetStatus.IN_SERVICE);
        asset.setCapitalisedGlEntryUid(journal.uid());
        asset.setUpdatedAt(Instant.now());
        asset.setUpdatedBy(actorId());

        return asset;
    }

    private AssetCategory requireCategory(Long companyId, Long categoryId) {
        AssetCategory cat = categories.findById(categoryId)
                .orElseThrow(() -> NotFoundException.of("AssetCategory", String.valueOf(categoryId)));
        if (!cat.getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException("Category does not belong to the specified company.");
        }
        return cat;
    }

    private FixedAsset require(String uid) {
        return assets.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FixedAsset", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private FixedAssetDto toDto(FixedAsset a) {
        return new FixedAssetDto(
                a.getId(), a.getUid(), a.getCompanyId(), a.getBranchId(),
                a.getAssetNumber(), a.getCategoryId(), a.getName(), a.getStatus(),
                a.getAcquisitionCost(), a.getSalvageValue(),
                a.getDepreciationMethod(), a.getLifePeriods(), a.getReducingRate(),
                a.getAcquisitionDate(), a.getDepreciationStartDate(),
                a.getCarryingCost(), a.getAccumulatedDepreciation(),
                a.computeNbv(),
                a.getRevaluationReserveBalance(),
                a.getSupplierId(), a.getSourceBillUid(),
                a.getLocation(), a.getCostCentreId(), a.getAssetTag(),
                a.getSerialNumber(), a.getModel(), a.getManufacturer(), a.getBarcode(),
                a.getParentAssetId(), a.getWarrantyExpiry(), a.getInsuredValue(), a.getInsurancePolicyNo(),
                a.getCapitalisedGlEntryUid(), a.getDisposedAt());
    }
}
