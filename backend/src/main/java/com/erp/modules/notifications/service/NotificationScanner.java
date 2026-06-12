package com.erp.modules.notifications.service;

import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.notifications.domain.entity.NotificationScanMarker;
import com.erp.modules.notifications.repository.NotificationScanMarkerRepository;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Time-based notification scanner — invoice-overdue + low-stock (ADR-0024 D-7).
 *
 * <p>Mirrors the {@code DomainEventDispatcher}'s {@code @Scheduled} pattern.
 * Runs per company on an hourly cadence (configurable via {@code erp.notifications.scanner.cron}).
 * Single-instance-safe via the scan markers (a double-run dedupes, BR-NOTIF-08).
 * Never writes to source tables (stock_on_hand / ar_invoices) — dedup state lives here.
 */
@Component
public class NotificationScanner {

    private static final Logger log = LoggerFactory.getLogger(NotificationScanner.class);

    static final String TYPE_INVOICE_OVERDUE = "INVOICE_OVERDUE";
    static final String TYPE_LOW_STOCK       = "LOW_STOCK";
    static final String PERM_AR_VIEW         = "AR.VIEW";
    static final String PERM_STOCK_VIEW      = "STOCK.VIEW";

    private final CompanyRepository                companies;
    private final ArInvoiceRepository              arInvoices;
    private final StockOnHandRepository            stockOnHand;
    private final NotificationScanMarkerRepository markers;
    private final NotificationRaiser               raiser;

    public NotificationScanner(CompanyRepository companies,
                                ArInvoiceRepository arInvoices,
                                StockOnHandRepository stockOnHand,
                                NotificationScanMarkerRepository markers,
                                NotificationRaiser raiser) {
        this.companies   = companies;
        this.arInvoices  = arInvoices;
        this.stockOnHand = stockOnHand;
        this.markers     = markers;
        this.raiser      = raiser;
    }

    /** Default hourly scan. Override with {@code erp.notifications.scanner.cron} (OQ-NOTIF-06). */
    @Scheduled(cron = "${erp.notifications.scanner.cron:0 0 * * * *}")
    public void scan() {
        List<Long> companyIds = companies.findAll()
                .stream().map(c -> c.getId()).toList();
        for (Long companyId : companyIds) {
            try {
                scanOverdue(companyId);
                scanLowStock(companyId);
            } catch (Exception ex) {
                log.error("NotificationScanner: error scanning company={}: {}", companyId, ex.getMessage(), ex);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Invoice-overdue scanner (D-7)
    // -----------------------------------------------------------------------

    @Transactional
    public void scanOverdue(Long companyId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        List<ArInvoice> overdue = arInvoices.findOverdueByCompany(companyId, today);

        for (ArInvoice invoice : overdue) {
            String conditionKey = invoice.getUid();
            Optional<NotificationScanMarker> existing =
                    markers.findByCompanyIdAndTypeKeyAndConditionKey(
                            companyId, TYPE_INVOICE_OVERDUE, conditionKey);
            if (existing.isPresent()) {
                // Already notified — skip (BR-NOTIF-08)
                continue;
            }
            // New overdue invoice — create marker and fire
            NotificationScanMarker marker = new NotificationScanMarker(
                    companyId, TYPE_INVOICE_OVERDUE, conditionKey);
            markers.save(marker);

            long daysOverdue = ChronoUnit.DAYS.between(invoice.getDueDate(), today);
            Map<String, String> vals = new HashMap<>();
            vals.put("invoiceNumber", invoice.getDocumentNo() != null ? invoice.getDocumentNo() : invoice.getUid());
            vals.put("customerName", "Customer#" + invoice.getCustomerId());
            vals.put("outstandingAmount", formatMoney(invoice.getOutstandingAmount(), invoice.getCurrency()));
            vals.put("daysOverdue", String.valueOf(daysOverdue));
            vals.put("sourceUid", invoice.getUid());

            String triggerKey = "scan:" + TYPE_INVOICE_OVERDUE + ":" + invoice.getUid();
            NotificationTrigger trigger = new NotificationTrigger(
                    companyId, invoice.getBranchId(), TYPE_INVOICE_OVERDUE, PERM_AR_VIEW,
                    "AR_INVOICE", invoice.getUid(), vals, triggerKey);
            try {
                raiser.raise(trigger);
                marker.recordNotified(Instant.now());
                markers.save(marker);
            } catch (Exception ex) {
                log.warn("NotificationScanner: failed to raise INVOICE_OVERDUE for invoice uid={}: {}",
                         invoice.getUid(), ex.getMessage());
            }
        }
    }

    // -----------------------------------------------------------------------
    // Low-stock scanner (D-7)
    // -----------------------------------------------------------------------

    @Transactional
    public void scanLowStock(Long companyId) {
        // Re-arm markers for rows now above reorder level (BR-NOTIF-08)
        List<StockOnHand> aboveReorder = stockOnHand.findAboveReorderByCompany(companyId);
        for (StockOnHand row : aboveReorder) {
            String conditionKey = row.getUid() + ":" + row.getBranchId();
            markers.findByCompanyIdAndTypeKeyAndConditionKey(companyId, TYPE_LOW_STOCK, conditionKey)
                    .filter(m -> !m.isArmed())
                    .ifPresent(m -> {
                        m.reArm(Instant.now());
                        markers.save(m);
                    });
        }

        // Fire notifications for rows at/below reorder level
        List<StockOnHand> atOrBelow = stockOnHand.findAtOrBelowReorderByCompany(companyId);
        for (StockOnHand row : atOrBelow) {
            String conditionKey = row.getUid() + ":" + row.getBranchId();
            Optional<NotificationScanMarker> existing =
                    markers.findByCompanyIdAndTypeKeyAndConditionKey(
                            companyId, TYPE_LOW_STOCK, conditionKey);

            if (existing.isPresent() && !existing.get().isArmed()) {
                // Already notified and not re-armed — skip (BR-NOTIF-08)
                continue;
            }

            // Either no marker or armed (recovered then dipped again) — fire
            NotificationScanMarker marker = existing.orElseGet(() ->
                    new NotificationScanMarker(companyId, TYPE_LOW_STOCK, conditionKey));

            Map<String, String> vals = new HashMap<>();
            vals.put("productName", "Product#" + row.getProductId());
            vals.put("branchName", "Branch#" + row.getBranchId());
            vals.put("onHandQty", row.getQuantity().toPlainString());
            vals.put("reorderLevel", row.getReorderLevel().toPlainString());
            vals.put("sourceUid", row.getUid());

            // Trigger key includes date to distinguish daily crossings
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            String triggerKey = "scan:" + TYPE_LOW_STOCK + ":" + row.getUid()
                                + ":" + row.getBranchId() + ":" + today;
            NotificationTrigger trigger = new NotificationTrigger(
                    companyId, row.getBranchId(), TYPE_LOW_STOCK, PERM_STOCK_VIEW,
                    "STOCK_ON_HAND", row.getUid(), vals, triggerKey);
            try {
                raiser.raise(trigger);
                marker.recordNotified(Instant.now());
                markers.save(marker);
            } catch (Exception ex) {
                log.warn("NotificationScanner: failed to raise LOW_STOCK for stock uid={}: {}",
                         row.getUid(), ex.getMessage());
            }
        }
    }

    private static String formatMoney(BigDecimal amount, String currency) {
        if (amount == null) return "0";
        String formatted = new DecimalFormat("#,##0.00").format(amount);
        return (currency != null ? currency + " " : "") + formatted;
    }
}
