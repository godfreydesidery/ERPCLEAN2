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
import java.util.Objects;
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
        // ADR-0056 D-2: the service/DB default stays EXCLUSIVE (entity default false) so existing
        // lists, direct-API callers and bulk price imports are never silently reinterpreted as
        // gross (grandfathering + import safety). "VAT-inclusive by default for NEW lists" is a
        // UI affordance: the price-list create form pre-checks the toggle and sends
        // priceIncludesVat=true explicitly. So here we simply honour whatever the request carries
        // (null → entity default false), never forcing a default.
        applyMetadata(pl, req.currency(), req.effectiveFrom(), req.effectiveTo(),
                req.priceIncludesVat(), req.isDefault(), req.scope());
        PriceList saved = priceLists.save(pl);
        // The default flag has always been bindable on this path; without clearing here, a create
        // that asks to be the default simply adds a second one, which is what the dedicated action
        // below exists to prevent.
        if (Boolean.TRUE.equals(req.isDefault())) {
            clearOtherDefaults(saved);
        }
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
        if (Boolean.TRUE.equals(req.isDefault())) {
            clearOtherDefaults(pl);
        }
        audit.record(AuditEvent.of(AuditActions.PRICELIST_UPDATE, "price_lists",
                pl.getId(), pl.getUid()));
        return PriceListDto.from(pl);
    }

    /**
     * Makes this the company's default list. Nothing in the system SET the flag before this action
     * existed, so most companies have none — which is why the stock reports print blank selling
     * prices and say "no default price list set" until someone decides.
     *
     * <p>An archived list is refused: reports resolve prices from ACTIVE lists only, so pointing the
     * default at an archived one would leave the company looking configured while still showing blank
     * prices.
     */
    @Override
    public PriceListDto setDefaultByUid(String uid) {
        PriceList pl = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pl.getCompanyId());
        if (pl.getStatus() != MasterStatus.ACTIVE) {
            throw new ConflictException("Only an active price list can be made the default. "
                    + "Restore this list first.");
        }
        clearOtherDefaults(pl);
        pl.setDefault(true);
        pl.setUpdatedAt(Instant.now());
        pl.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRICELIST_SET_DEFAULT, "price_lists",
                        pl.getId(), pl.getUid())
                .detail(Map.of("code", pl.getCode(), "name", pl.getName())));
        return PriceListDto.from(pl);
    }

    /**
     * Archives the list, and surrenders the default flag with it.
     *
     * <p>Price resolution only ever considers ACTIVE lists, so an archived row holding the flag is a
     * default that answers nothing: the reports fall back to "no default price list set" and print
     * blank selling prices, while the Price Lists screen still shows a Default badge on the archived
     * row. Clearing it here keeps the flag honest and matches
     * {@link #setDefaultByUid(String)}, which refuses to point the default at an archived list in
     * the first place. Someone must then name a live list as the new default — a deliberate choice,
     * which is the point.
     */
    @Override
    public void archiveByUid(String uid) {
        PriceList pl = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pl.getCompanyId());
        MasterStatus prev = pl.getStatus();
        pl.setStatus(MasterStatus.ARCHIVED);
        pl.setDefault(false);
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
     * Clears the default flag on every OTHER list in the same company, so "the default price list" is
     * a question with one answer.
     *
     * <p>Loops rather than taking the first hit: there is no partial unique index behind the column,
     * and the flag was bindable through create/update before this existed, so a company can arrive
     * here already holding several. Clearing all of them makes the next set self-healing.
     *
     * <p>App-level only. Two genuinely concurrent set-default calls could still both land, but price
     * lists are administered one at a time by one person, and closing that window means a unique
     * index and therefore a migration.
     */
    private void clearOtherDefaults(PriceList keep) {
        Long actorId = actorId();
        for (PriceList other : priceLists.findDefaultsOfCompany(keep.getCompanyId())) {
            if (!Objects.equals(other.getId(), keep.getId())) {
                other.setDefault(false);
                other.setUpdatedAt(Instant.now());
                other.setUpdatedBy(actorId);
                priceLists.save(other);
            }
        }
    }

    /**
     * Applies the optional P2 D5 pricing-resolution metadata. Every field is optional on both the
     * create and the update request, so each is only overwritten when the request actually supplied
     * a value (null = keep what the row already holds, or the entity default on create).
     *
     * <p>{@code currency}, {@code effectiveFrom} and {@code effectiveTo} used to be written
     * unconditionally, which meant any partial update silently wiped all three: the rename form
     * sends {name, priceIncludesVat} and the set-default form sends {name, isDefault}, neither
     * carries the currency or the validity window, and {@link CurrencyCode#ofNullable} maps a
     * missing currency to null rather than rejecting it — so the loss was silent, with no 400 to
     * hint at it. They are now guarded exactly like the fields below.
     */
    private static void applyMetadata(PriceList pl, String currency,
                                      java.time.LocalDate effectiveFrom,
                                      java.time.LocalDate effectiveTo, Boolean priceIncludesVat,
                                      Boolean isDefault, PriceListScope scope) {
        if (currency != null) {
            pl.setCurrency(CurrencyCode.ofNullable(currency));
        }
        if (effectiveFrom != null) {
            pl.setEffectiveFrom(effectiveFrom);
        }
        if (effectiveTo != null) {
            pl.setEffectiveTo(effectiveTo);
        }
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
