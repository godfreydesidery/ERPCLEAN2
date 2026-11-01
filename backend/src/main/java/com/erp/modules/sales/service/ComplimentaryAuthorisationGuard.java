package com.erp.modules.sales.service;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.TenancyScopeEnforcer;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * A COMPLIMENTARY tender needs {@code SALES.DISCOUNT.OVERRIDE}: the cashier's own, or a manager's
 * step-up re-verified like {@link DiscountAuthorisationGuard} (same tenant, active, not the
 * cashier). Every approval is audited.
 */
@Component
public class ComplimentaryAuthorisationGuard {

    private static final Logger log = LoggerFactory.getLogger(ComplimentaryAuthorisationGuard.class);

    static final String PERMISSION = DiscountAuthorisationGuard.DISCOUNT_OVERRIDE_PERMISSION;
    static final String APPROVAL_REQUIRED = "COMPLIMENTARY_APPROVAL_REQUIRED";

    private final AppUserRepository users;
    private final PermissionResolver permissionResolver;
    private final AuditService audit;
    private final TenancyScopeEnforcer tenancy;

    public ComplimentaryAuthorisationGuard(AppUserRepository users,
                                           PermissionResolver permissionResolver,
                                           AuditService audit,
                                           TenancyScopeEnforcer tenancy) {
        this.users = users;
        this.permissionResolver = permissionResolver;
        this.audit = audit;
        this.tenancy = tenancy;
    }

    /** Allows {@code amount} of a sale as COMPLIMENTARY, or refuses (422, nothing charged). */
    public void authorise(Long companyId, Long invoiceId, String invoiceUid, BigDecimal amount,
                          String authoriserUid) {
        RequestContext.Principal caller = RequestContext.get();
        if (permissionResolver.hasPermission(caller, PERMISSION, System.currentTimeMillis())) {
            record(invoiceId, invoiceUid, amount, caller != null ? caller.username() : null, "HELD");
            return;
        }
        if (authoriserUid == null || authoriserUid.isBlank()) {
            throw PosSaleFlowException.rejected(
                    "A complimentary sale needs a manager's approval.", APPROVAL_REQUIRED);
        }
        AppUser authoriser = users.findByUid(authoriserUid.trim()).orElse(null);
        if (authoriser != null && !tenancy.isSameTenant(caller, authoriser.getOrganisationId())) {
            log.warn("Cross-tenant complimentary approval refused on invoice {}", invoiceUid);
            authoriser = null;
        }
        if (authoriser == null || !authoriser.isActive()
                || (caller != null && caller.userId() != null
                        && caller.userId().equals(authoriser.getId()))) {
            log.warn("Complimentary approval not accepted on invoice {} (company {}).",
                    invoiceUid, companyId);
            throw notAccepted();
        }
        RequestContext.Principal inSaleScope = new RequestContext.Principal(
                authoriser.getId(), authoriser.getUsername(), authoriser.isRoot(), companyId,
                caller != null ? caller.branchId() : null, caller != null ? caller.ip() : null,
                authoriser.getOrganisationId());
        if (!permissionResolver.hasPermission(inSaleScope, PERMISSION, System.currentTimeMillis())) {
            log.warn("Complimentary approval on invoice {}: the authoriser lacks {}.",
                    invoiceUid, PERMISSION);
            throw notAccepted();
        }
        record(invoiceId, invoiceUid, amount, authoriser.getUsername(), "APPROVED");
    }

    private static PosSaleFlowException notAccepted() {
        return PosSaleFlowException.rejected(
                "That approval was not accepted. A manager who can approve discounts must confirm "
                        + "the complimentary sale.", APPROVAL_REQUIRED);
    }

    private void record(Long invoiceId, String invoiceUid, BigDecimal amount, String by,
                        String how) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("amount", amount.toPlainString());
        detail.put("authorisedBy", by == null ? "" : by);
        detail.put("authority", how);
        audit.record(AuditEvent.of(AuditActions.POS_SALE_COMPLIMENTARY, "sales_invoices",
                invoiceId, invoiceUid).detail(detail));
    }
}
