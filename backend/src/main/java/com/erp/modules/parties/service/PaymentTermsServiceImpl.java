package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.CreatePaymentTermsRequest;
import com.erp.modules.parties.domain.dto.PaymentTermsDto;
import com.erp.modules.parties.domain.dto.UpdatePaymentTermsRequest;
import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
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

/** PaymentTerms master administration (D-2, ADR-0040). */
@Service
@Transactional
public class PaymentTermsServiceImpl implements PaymentTermsService {

    private final PaymentTermsRepository paymentTermsRepo;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public PaymentTermsServiceImpl(PaymentTermsRepository paymentTermsRepo,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.paymentTermsRepo = paymentTermsRepo;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public PaymentTermsDto create(CreatePaymentTermsRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        validateDiscount(req.discountDays(), req.discountPercent());

        if (paymentTermsRepo.existsByCompanyIdAndCode(req.companyId(), req.code())) {
            throw new ConflictException("Payment terms code '" + req.code()
                    + "' already exists in this company.");
        }

        PaymentTerms pt = new PaymentTerms(req.companyId(), req.code(), req.name(),
                req.basis(), req.netDays(), actorId());
        pt.setDiscountDays(req.discountDays());
        pt.setDiscountPercent(req.discountPercent());

        PaymentTerms saved = paymentTermsRepo.save(pt);
        audit.record(AuditEvent.of(AuditActions.PAYMENTTERMS_CREATE, "payment_terms",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(), "basis", saved.getBasis().name())));
        return PaymentTermsDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentTermsDto getByUid(String uid) {
        PaymentTerms pt = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pt.getCompanyId());
        return PaymentTermsDto.from(pt);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PaymentTermsDto> list(Long companyId, String q, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return paymentTermsRepo.search(companyId, q, pageable).map(PaymentTermsDto::from);
        }
        return paymentTermsRepo.findByCompanyId(companyId, pageable).map(PaymentTermsDto::from);
    }

    @Override
    public PaymentTermsDto updateByUid(String uid, UpdatePaymentTermsRequest req) {
        PaymentTerms pt = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pt.getCompanyId());
        validateDiscount(req.discountDays(), req.discountPercent());

        pt.setCode(req.code());
        pt.setName(req.name());
        pt.setBasis(req.basis());
        pt.setNetDays(req.netDays());
        pt.setDiscountDays(req.discountDays());
        pt.setDiscountPercent(req.discountPercent());
        pt.setUpdatedAt(Instant.now());
        pt.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.PAYMENTTERMS_UPDATE, "payment_terms",
                pt.getId(), pt.getUid()));
        return PaymentTermsDto.from(pt);
    }

    @Override
    public void archiveByUid(String uid) {
        PaymentTerms pt = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pt.getCompanyId());
        pt.setStatus(MasterStatus.ARCHIVED);
        pt.setUpdatedAt(Instant.now());
        pt.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PAYMENTTERMS_ARCHIVE, "payment_terms",
                pt.getId(), pt.getUid()));
    }

    @Override
    public void restoreByUid(String uid) {
        PaymentTerms pt = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), pt.getCompanyId());
        pt.setStatus(MasterStatus.ACTIVE);
        pt.setUpdatedAt(Instant.now());
        pt.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PAYMENTTERMS_RESTORE, "payment_terms",
                pt.getId(), pt.getUid()));
    }

    // -------------------------------------------------------------------------

    private PaymentTerms require(String uid) {
        return Lookups.orNotFound(paymentTermsRepo.findByUid(uid), "PaymentTerms", uid);
    }

    private void validateDiscount(Integer discountDays, java.math.BigDecimal discountPercent) {
        boolean daysSet = discountDays != null;
        boolean pctSet = discountPercent != null;
        if (daysSet != pctSet) {
            throw new IllegalArgumentException(
                    "discount_days and discount_percent must be both set or both null (D-2 both-null-or-both-set rule).");
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
