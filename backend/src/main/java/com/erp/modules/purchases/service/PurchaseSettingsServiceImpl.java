package com.erp.modules.purchases.service;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.purchases.domain.dto.PurchaseSettingsDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseSettingsRequest;
import com.erp.modules.purchases.domain.enums.PurchaseVatTreatment;
import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.repository.PurchaseSettingsRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PurchaseSettingsServiceImpl implements PurchaseSettingsService {

    private final PurchaseSettingsRepository settings;
    private final CompanyRepository          companies;
    private final ScopeGuard                 scopeGuard;

    public PurchaseSettingsServiceImpl(PurchaseSettingsRepository settings,
                                       CompanyRepository companies,
                                       ScopeGuard scopeGuard) {
        this.settings   = settings;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    @Override
    @Transactional(readOnly = true)
    public PurchaseSettingsDto getByCompanyUid(String companyUid) {
        Long companyId = resolveCompanyId(companyUid);
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        PurchaseSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> defaultSettings(companyId));
        return PurchaseSettingsDto.from(s);
    }

    @Override
    public PurchaseSettingsDto update(UpdatePurchaseSettingsRequest req) {
        Long companyId = resolveCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        PurchaseSettings s = settings.findByCompanyId(companyId)
                .orElseGet(() -> {
                    PurchaseSettings created = new PurchaseSettings(companyId, actorId());
                    return settings.save(created);
                });

        s.setPoApprovalEnabled(req.poApprovalEnabled());
        s.setPoApprovalThresholdAmount(req.poApprovalThresholdAmount());
        if (req.currency() != null && !req.currency().isBlank()) {
            s.setCurrency(CurrencyCode.ofNullable(req.currency()));
        }
        // P2 D7 — procurement policy defaults. These are NOT surfaced by any current UI, so a partial
        // update (e.g. the settings form, which only sends the approval + receipt-tolerance fields)
        // must LEAVE THEM UNCHANGED rather than wipe them to null — null = leave unchanged (matches the
        // Boolean tri-state handling below). Fields the settings form DOES own (poApprovalThreshold,
        // receiptTolerancePct) are set unconditionally above/below so a blank there still clears them.
        if (req.defaultPaymentTermsId() != null) {
            s.setDefaultPaymentTermsId(req.defaultPaymentTermsId());
        }
        if (req.defaultLocationId() != null) {
            s.setDefaultLocationId(req.defaultLocationId());
        }
        if (req.matchTolerancePct() != null) {
            s.setMatchTolerancePct(req.matchTolerancePct());
        }
        if (req.matchToleranceAbs() != null) {
            s.setMatchToleranceAbs(req.matchToleranceAbs());
        }
        if (req.requisitionApprovalThresholdAmount() != null) {
            s.setRequisitionApprovalThresholdAmount(req.requisitionApprovalThresholdAmount());
        }
        // Owned by the settings form (always sent) — set unconditionally so blank clears them.
        // Range-checked here (not via annotation) so the message stays field-name-free.
        if (req.receiptTolerancePct() != null && req.receiptTolerancePct().signum() < 0) {
            throw new IllegalArgumentException("Over-receipt tolerance cannot be negative.");
        }
        s.setReceiptTolerancePct(req.receiptTolerancePct());
        if (req.autoCloseEnabled() != null) {
            s.setAutoCloseEnabled(req.autoCloseEnabled());
        }
        if (req.requisitionApprovalEnabled() != null) {
            s.setRequisitionApprovalEnabled(req.requisitionApprovalEnabled());
        }
        // V105 — null leaves it unchanged. An unknown value is REFUSED rather than quietly
        // defaulted: silently falling back to EXCLUSIVE would tell a shop that asked for INCLUSIVE
        // that it had been set, and they would find out from a printed note that still double-counts.
        if (req.purchaseVatTreatment() != null && !req.purchaseVatTreatment().isBlank()) {
            s.setPurchaseVatTreatment(parseVatTreatment(req.purchaseVatTreatment()));
        }
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(actorId());
        return PurchaseSettingsDto.from(settings.save(s));
    }

    // -------------------------------------------------------------------------

    /**
     * Reads the requested VAT treatment, or refuses with a message a shopkeeper can act on.
     * Deliberately not {@code PurchaseVatTreatment.orDefault} — that lenient reader exists for
     * data already in the column, where today's behaviour is the safe fallback. A value arriving
     * from a form is a request, and a request that cannot be honoured must say so.
     */
    private static PurchaseVatTreatment parseVatTreatment(String raw) {
        try {
            return PurchaseVatTreatment.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Choose how purchase costs are entered: prices exclude VAT, prices include VAT, "
                    + "or no VAT.");
        }
    }

    /** Returns an unsaved default; callers within a readOnly tx get a transient fallback. */
    private PurchaseSettings defaultSettings(Long companyId) {
        return new PurchaseSettings(companyId, null);
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
