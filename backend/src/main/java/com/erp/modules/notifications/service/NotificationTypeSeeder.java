package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.entity.NotificationType;
import com.erp.modules.notifications.domain.enums.NotificationChannel;
import com.erp.modules.notifications.domain.enums.NotificationSeverity;
import com.erp.modules.notifications.repository.NotificationTypeRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts the canonical 6 notification-type rows for a new company on bootstrap (ADR-0024 D-9).
 *
 * <p>These types mirror the V25 CROSS JOIN seed so that newly created companies (after the initial
 * bootstrap run) also get their catalogue without requiring a manual DB patch.
 * Idempotent: a duplicate insert is silently skipped via the unique constraint on
 * (company_id, type_key).
 */
@Service
public class NotificationTypeSeeder {

    private static final Logger log = LoggerFactory.getLogger(NotificationTypeSeeder.class);

    private final NotificationTypeRepository types;

    public NotificationTypeSeeder(NotificationTypeRepository types) {
        this.types = types;
    }

    /** Seed default notification types for {@code companyId}. Call inside an active TX. */
    @Transactional
    public void seedDefaults(Long companyId) {
        List<TypeSpec> specs = List.of(
                new TypeSpec("GOODS_RECEIVED",     "Goods Received",
                        "STOCK.VIEW", false,
                        NotificationSeverity.INFO,
                        "Goods received: {documentNo}",
                        "Stock receipt {documentNo} has been processed.",
                        "/stock/receipts/{sourceUid}"),
                new TypeSpec("DELIVERY_CONFIRMED", "Delivery Confirmed",
                        "SALES.VIEW", true,
                        NotificationSeverity.INFO,
                        "Delivery confirmed: {documentNo}",
                        "Delivery {documentNo} has been confirmed.",
                        "/sales/deliveries/{sourceUid}"),
                new TypeSpec("PAYMENT_RECEIVED",   "Payment Received",
                        "AR.VIEW", false,
                        NotificationSeverity.INFO,
                        "Payment received from {customerName}",
                        "Receipt of {amountFormatted} from {customerName} has been recorded.",
                        "/ar/receipts/{sourceUid}"),
                new TypeSpec("INVOICE_OVERDUE",    "Invoice Overdue",
                        "AR.VIEW", false,
                        NotificationSeverity.WARNING,
                        "Invoice overdue: {documentNo}",
                        "Invoice {documentNo} is overdue.",
                        "/ar/invoices/{sourceUid}"),
                new TypeSpec("LOW_STOCK",          "Low Stock Alert",
                        "STOCK.VIEW", true,
                        NotificationSeverity.WARNING,
                        "Low stock: {productName}",
                        "Stock for {productName} has fallen at or below the reorder level.",
                        "/stock/on-hand"),
                new TypeSpec("APPROVAL_PENDING",   "Approval Pending",
                        "PURCHASE.PO.APPROVE", false,
                        NotificationSeverity.INFO,
                        "Approval required: {documentType} {documentUid}",
                        "{documentType} submitted by {submittedBy} requires your approval.",
                        "/approvals/{sourceUid}")
        );

        for (TypeSpec spec : specs) {
            if (types.findByCompanyIdAndTypeKey(companyId, spec.typeKey()).isPresent()) {
                continue; // already seeded — skip
            }
            NotificationType nt = new NotificationType();
            nt.setCompanyId(companyId);
            nt.setTypeKey(spec.typeKey());
            nt.setDisplayName(spec.displayName());
            nt.setAudiencePermission(spec.audiencePermission());
            nt.setBranchScoped(spec.branchScoped());
            nt.setDefaultChannels(NotificationChannel.IN_APP.name());
            nt.setSeverity(spec.severity().name());
            nt.setTitleTemplate(spec.titleTemplate());
            nt.setBodyTemplate(spec.bodyTemplate());
            nt.setLinkRouteTemplate(spec.linkRouteTemplate());
            nt.setCompanyEnabled(true);
            nt.setStatus(MasterStatus.ACTIVE);
            nt.setCreatedAt(Instant.now());
            nt.setCreatedBy(null); // system seeder — no actor
            types.save(nt);
        }
        log.info("Notification type catalogue seeded for company {}.", companyId);
    }

    private record TypeSpec(
            String typeKey,
            String displayName,
            String audiencePermission,
            boolean branchScoped,
            NotificationSeverity severity,
            String titleTemplate,
            String bodyTemplate,
            String linkRouteTemplate
    ) {}
}
