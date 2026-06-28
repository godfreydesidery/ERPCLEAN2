package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.AddVatAdjustmentRequest;
import com.erp.modules.tax.domain.dto.VatAdjustmentDto;
import com.erp.modules.tax.domain.entity.VatAdjustment;
import com.erp.modules.tax.domain.entity.VatReturn;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.repository.VatAdjustmentRepository;
import com.erp.modules.tax.repository.VatReturnRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual VAT adjustment add/remove (ADR-0017 D-3, FR-VAT-05/06, BR-VAT-09).
 */
@Service
@Transactional
public class VatAdjustmentServiceImpl implements VatAdjustmentService {

    private static final String VAT_RETURN = "VatReturn";

    private final VatAdjustmentRepository adjustments;
    private final VatReturnRepository     returns;
    private final ScopeGuard              scopeGuard;
    private final AuditService            audit;

    public VatAdjustmentServiceImpl(VatAdjustmentRepository adjustments,
                                     VatReturnRepository returns,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.adjustments = adjustments;
        this.returns     = returns;
        this.scopeGuard  = scopeGuard;
        this.audit       = audit;
    }

    @Override
    public VatAdjustmentDto addAdjustment(String vatReturnUid, AddVatAdjustmentRequest req) {
        VatReturn vatReturn = Lookups.orNotFound(returns.findByUid(vatReturnUid), VAT_RETURN, vatReturnUid);
        scopeGuard.assertCanActIn(RequestContext.get(), vatReturn.getCompanyId());

        if (vatReturn.getStatus() == VatReturnStatus.FILED) {
            // BR-VAT-09: filed returns cannot be adjusted
            throw new IllegalStateException(
                    "This VAT return has already been filed, so it can no longer be adjusted.");
        }

        VatAdjustment adjustment = new VatAdjustment(
                vatReturn.getId(), vatReturn.getCompanyId(),
                req.reason(), req.sign(), req.amount(), req.narrative(), actorId());
        adjustment = adjustments.save(adjustment);

        audit.record(AuditEvent.of(AuditActions.VAT_ADJUST, "vat_adjustments",
                adjustment.getId(), adjustment.getUid())
                .detail(Map.of("vatReturnUid", vatReturnUid,
                        "sign", req.sign().name(),
                        "amount", req.amount().toPlainString())));

        return toDto(adjustment);
    }

    @Override
    public void removeAdjustment(String vatReturnUid, String adjustmentUid) {
        VatReturn vatReturn = Lookups.orNotFound(returns.findByUid(vatReturnUid), VAT_RETURN, vatReturnUid);
        scopeGuard.assertCanActIn(RequestContext.get(), vatReturn.getCompanyId());

        if (vatReturn.getStatus() == VatReturnStatus.FILED) {
            throw new IllegalStateException(
                    "Cannot remove an adjustment from a FILED VAT return.");
        }

        VatAdjustment adjustment = adjustments.findByUid(adjustmentUid)
                .orElseThrow(() -> new NotFoundException("VAT adjustment not found."));

        if (!adjustment.getVatReturnId().equals(vatReturn.getId())) {
            throw new NotFoundException("VAT adjustment not found on this return.");
        }

        adjustments.delete(adjustment);

        audit.record(AuditEvent.of(AuditActions.VAT_ADJUST, "vat_adjustments",
                adjustment.getId(), adjustmentUid)
                .detail(Map.of("vatReturnUid", vatReturnUid, "action", "remove")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<VatAdjustmentDto> listByReturn(String vatReturnUid) {
        VatReturn vatReturn = Lookups.orNotFound(returns.findByUid(vatReturnUid), VAT_RETURN, vatReturnUid);
        scopeGuard.assertCanActIn(RequestContext.get(), vatReturn.getCompanyId());
        return adjustments.findByVatReturnId(vatReturn.getId()).stream()
                .map(this::toDto)
                .toList();
    }

    // -------------------------------------------------------------------------

    private VatAdjustmentDto toDto(VatAdjustment a) {
        return new VatAdjustmentDto(
                a.getId(), a.getUid(), a.getVatReturnId(), a.getCompanyId(),
                a.getReason(), a.getSign(), a.getAmount(), a.getNarrative());
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
