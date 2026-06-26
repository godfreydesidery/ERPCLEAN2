package com.erp.modules.fixedassets.service;

import com.erp.modules.fixedassets.domain.dto.AssetCategoryDto;
import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AssetCategoryServiceImpl implements AssetCategoryService {

    private final AssetCategoryRepository categories;
    private final ChartOfAccountRepository glAccounts;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public AssetCategoryServiceImpl(AssetCategoryRepository categories,
                                     ChartOfAccountRepository glAccounts,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.categories = categories;
        this.glAccounts = glAccounts;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public AssetCategoryDto create(CreateAssetCategoryRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());

        // Validate all three GL account ids belong to the same company (tenant-isolation site 1).
        requireAccountInCompany(req.companyId(), req.assetAccountId());
        requireAccountInCompany(req.companyId(), req.accumDepAccountId());
        requireAccountInCompany(req.companyId(), req.depExpenseAccountId());

        if (categories.findByCompanyIdAndCode(req.companyId(), req.code()).isPresent()) {
            throw new ConflictException(
                    "Asset category code '" + req.code() + "' already exists in this company.");
        }

        AssetCategory cat = new AssetCategory(
                req.companyId(), req.code(), req.name(),
                req.defaultMethod(), req.defaultLifePeriods(), req.defaultReducingRate(),
                req.assetAccountId(), req.accumDepAccountId(), req.depExpenseAccountId(),
                actorId());
        cat = categories.save(cat);

        audit.record(AuditEvent.of(AuditActions.FA_CATEGORY_CREATE, "asset_categories",
                        cat.getId(), cat.getUid())
                .detail(Map.of("code", cat.getCode(), "name", cat.getName())));

        return toDto(cat);
    }

    @Override
    @Transactional(readOnly = true)
    public AssetCategoryDto getByUid(String uid) {
        AssetCategory cat = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cat.getCompanyId());
        return toDto(cat);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AssetCategoryDto> listByCompany(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return categories.findByCompanyId(companyId).stream().map(this::toDto).toList();
    }

    @Override
    public AssetCategoryDto update(String uid, UpdateAssetCategoryRequest req) {
        AssetCategory cat = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cat.getCompanyId());

        // Validate all three GL account ids belong to the category's company (tenant-isolation site 1).
        requireAccountInCompany(cat.getCompanyId(), req.assetAccountId());
        requireAccountInCompany(cat.getCompanyId(), req.accumDepAccountId());
        requireAccountInCompany(cat.getCompanyId(), req.depExpenseAccountId());

        cat.setName(req.name());
        cat.setDefaultMethod(req.defaultMethod());
        cat.setDefaultLifePeriods(req.defaultLifePeriods());
        cat.setDefaultReducingRate(req.defaultReducingRate());
        cat.setAssetAccountId(req.assetAccountId());
        cat.setAccumDepAccountId(req.accumDepAccountId());
        cat.setDepExpenseAccountId(req.depExpenseAccountId());
        cat.setUpdatedAt(Instant.now());
        cat.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.FA_CATEGORY_UPDATE, "asset_categories",
                        cat.getId(), cat.getUid())
                .detail(Map.of("name", cat.getName())));

        return toDto(cat);
    }

    @Override
    public AssetCategoryDto archive(String uid) {
        AssetCategory cat = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cat.getCompanyId());
        cat.setStatus(MasterStatus.ARCHIVED);
        cat.setUpdatedAt(Instant.now());
        cat.setUpdatedBy(actorId());
        return toDto(cat);
    }

    // -------------------------------------------------------------------------

    /**
     * Asserts that {@code accountId} belongs to {@code companyId}. Throws {@link NotFoundException}
     * (404 — no existence leak) when the account does not exist in that company. This prevents a
     * caller from poisoning a category with GL accounts from a foreign tenant (confused-deputy,
     * tenant-isolation site 1).
     */
    private void requireAccountInCompany(Long companyId, Long accountId) {
        if (!glAccounts.existsByIdAndCompanyId(accountId, companyId)) {
            throw NotFoundException.of("ChartOfAccount", String.valueOf(accountId));
        }
    }

    private AssetCategory require(String uid) {
        return categories.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("AssetCategory", uid));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private AssetCategoryDto toDto(AssetCategory c) {
        return new AssetCategoryDto(
                c.getId(), c.getUid(), c.getCompanyId(),
                c.getCode(), c.getName(),
                c.getDefaultMethod(), c.getDefaultLifePeriods(), c.getDefaultReducingRate(),
                c.getAssetAccountId(), c.getAccumDepAccountId(), c.getDepExpenseAccountId(),
                c.getStatus());
    }
}
