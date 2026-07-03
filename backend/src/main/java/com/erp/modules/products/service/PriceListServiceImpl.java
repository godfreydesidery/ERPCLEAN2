package com.erp.modules.products.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.UpdatePriceListRequest;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.enums.PriceListScope;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.platform.common.money.CurrencyCode;
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
 * PriceList master administration (FR-PROD-10, ADR-0007 D-7).
 * All three security findings replicated from Parties (brief §3.1).
 */
@Service
@Transactional
public class PriceListServiceImpl implements PriceListService {

    private final PriceListRepository priceLists;
    private final CompanyRepository companies;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public PriceListServiceImpl(PriceListRepository priceLists,
                                CompanyRepository companies,
                                ScopeGuard scopeGuard,
                                AuditService audit) {
        this.priceLists = priceLists;
        this.companies = companies;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public PriceListDto create(CreatePriceListRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Pre-check the real unique key (uq_price_list_company_code: company_id, code) so the
        // caller gets a specific, friendly 409 instead of the generic DB-constraint catch-all
        // (error-message-hygiene defect D2).
        if (priceLists.existsByCompanyIdAndCode(companyId, req.code())) {
            throw new ConflictException("A price list with code " + req.code() + " already exists.");
        }

        PriceList pl = new PriceList(companyId, req.code(), req.name(), actorId());
        applyMetadata(pl, req.currency(), req.effectiveFrom(), req.effectiveTo(),
                req.priceIncludesVat(), req.isDefault(), req.scope());
        PriceList saved = priceLists.save(pl);
        audit.record(AuditEvent.of(AuditActions.PRICELIST_CREATE, "price_lists",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(), "name", saved.getName())));
        return PriceListDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PriceListDto getByUid(String uid) {
        // Security fix (finding 2)
        PriceList pl = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pl.getCompanyId());
        return PriceListDto.from(pl);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PriceListDto> list(Long companyId, String q, Pageable pageable) {
        // Security fix (finding 1)
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return priceLists.search(companyId, q, pageable).map(PriceListDto::from);
        }
        return priceLists.findByCompanyId(companyId, pageable).map(PriceListDto::from);
    }

    @Override
    public PriceListDto updateByUid(String uid, UpdatePriceListRequest req) {
        PriceList pl = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pl.getCompanyId());
        pl.setName(req.name());
        applyMetadata(pl, req.currency(), req.effectiveFrom(), req.effectiveTo(),
                req.priceIncludesVat(), req.isDefault(), req.scope());
        pl.setUpdatedAt(Instant.now());
        pl.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRICELIST_UPDATE, "price_lists",
                pl.getId(), pl.getUid()));
        return PriceListDto.from(pl);
    }

    @Override
    public void archiveByUid(String uid) {
        PriceList pl = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pl.getCompanyId());
        MasterStatus prev = pl.getStatus();
        pl.setStatus(MasterStatus.ARCHIVED);
        pl.setUpdatedAt(Instant.now());
        pl.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRICELIST_ARCHIVE, "price_lists",
                        pl.getId(), pl.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        PriceList pl = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pl.getCompanyId());
        MasterStatus prev = pl.getStatus();
        pl.setStatus(MasterStatus.ACTIVE);
        pl.setUpdatedAt(Instant.now());
        pl.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRICELIST_RESTORE, "price_lists",
                        pl.getId(), pl.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private PriceList require(String uid) {
        return Lookups.orNotFound(priceLists.findByUid(uid), "PriceList", uid);
    }

    /**
     * Applies the optional P2 D5 pricing-resolution metadata. {@code currency} is parsed via
     * {@link CurrencyCode#ofNullable}; dates are set as-is (null = open-ended). The defaulted
     * {@code priceIncludesVat}/{@code isDefault}/{@code scope} are only overwritten when the request
     * supplied a value (null = keep the entity default).
     */
    private static void applyMetadata(PriceList pl, String currency,
                                      java.time.LocalDate effectiveFrom,
                                      java.time.LocalDate effectiveTo, Boolean priceIncludesVat,
                                      Boolean isDefault, PriceListScope scope) {
        pl.setCurrency(CurrencyCode.ofNullable(currency));
        pl.setEffectiveFrom(effectiveFrom);
        pl.setEffectiveTo(effectiveTo);
        if (priceIncludesVat != null) {
            pl.setPriceIncludesVat(priceIncludesVat);
        }
        if (isDefault != null) {
            pl.setDefault(isDefault);
        }
        if (scope != null) {
            pl.setScope(scope);
        }
    }

    private Long resolveCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
