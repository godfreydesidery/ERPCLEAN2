package com.erp.modules.sales.service;

import com.erp.modules.products.service.LeafCostResolver;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.domain.enums.BelowCostAction;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the per-company "sale at or below cost" Sales Setting
 * ({@code sales_settings.below_cost_action}, V93, owner decision 2026-08-01) at sales-invoice
 * finalise — which is also the POS path, since {@link PosSaleServiceImpl} rings a sale by
 * delegating to {@link SalesInvoiceServiceImpl#finalise}. Structural sibling of
 * {@link NegativeStockGuard}: same {@code MANDATORY} propagation, same read-the-setting-once shape,
 * same friendly {@link ConflictException}.
 *
 * <p><b>Why finalise is the enforcement point.</b> A draft invoice is still being negotiated — a
 * line can be re-priced or removed. Finalise is where the price is frozen, revenue is committed and
 * the outbox event is emitted; rejecting here, before the transaction commits, is the last moment a
 * loss-making sale can still be stopped without a void/credit-note. Both point-of-sale origins run
 * through it: the till is a DIRECT-class invoice and finalises here too.
 *
 * <p><b>The caller applies this to DIRECT and POS invoices only</b> (owner decision 2026-08-01) —
 * the origins where the price is agreed at the moment of sale and nothing has shipped. An SO-billed
 * invoice is deliberately exempt: its delivery already issued the stock, so refusing at finalise
 * would strand goods the customer physically holds, with no way to raise the invoice and no
 * override under {@code BLOCK}. Below-cost order pricing is a question to catch on the order, not a
 * reason to refuse the paperwork afterwards.
 *
 * <p><b>The comparison is on the NET unit price, per base unit, after discounts.</b> The caller
 * hands over lines whose {@code netAmount} has just been produced by
 * {@link InvoiceTotalsCalculator#recompute} — so the VAT maths is not reimplemented here. That
 * matters: on a VAT-inclusive price list (ADR-0056) {@code unit_price_amount} holds the GROSS
 * amount, and comparing gross against cost would let a line sitting 18% UNDER cost sail through.
 * {@code netAmount} is post-line-discount and post-apportioned-document-discount, so a sale
 * discounted into a loss is caught as well. Dividing by {@code qtyInBase} puts the price in the
 * same unit as the cost — a pack line compares pack-net-per-base against per-base cost, not
 * pack price against unit cost.
 *
 * <p><b>Cost basis: moving average (owner decision).</b> Read through
 * {@link LeafCostResolver#avgCosts} — the cross-module-safe port declared in {@code products} and
 * implemented in {@code stock} (ADR-0026 D-10), which already weights across a branch's stock
 * locations. This guard makes exactly ONE call for the whole invoice, carrying every DISTINCT
 * product id, so repeated lines of the same product cost nothing extra. Note the honest caveat:
 * today's {@code StockLeafCostResolver} still issues one SELECT per product id inside that call
 * (its own javadoc records the N+1), so a basket of N distinct products is N queries, not one.
 * That is acceptable at till volumes and only happens when the policy is not OFF, but batching the
 * resolver is the obvious next optimisation if a large basket ever shows up in latency.
 *
 * <p><b>Where this guard deliberately does NOT protect you (fail-open surfaces).</b> Each of these
 * lets a line through under EVERY action, including BLOCK. They are recorded (log + a
 * {@code SALES.BELOW_COST.WARNING} audit row naming the products) rather than silently dropped, but
 * the owner must not read "BLOCK" as "nothing can ever be sold under cost":
 * <ul>
 *   <li><b>No established cost</b> — never received, or received before costing was in use. Owner
 *       decision: blocking on a missing cost would make the policy fire hardest exactly where it
 *       knows least, and would jam a till over a data gap.</li>
 *   <li><b>An {@code avg_cost} of zero</b> is read as "not costed yet", not as "this item is free" —
 *       otherwise a {@code <=} against zero would reject every line for a company that has never
 *       costed its stock.</li>
 *   <li><b>Non-stockable products (services, fees)</b> hold no {@code stock_on_hand} row, so they
 *       have no cost to compare and always pass. Correct for a service, but it does mean a
 *       service-only invoice is never checked.</li>
 *   <li><b>Kits and phantoms that explode at issue</b> (ADR-0058 — a POS kit with
 *       {@code product_components}, or a non-stockable phantom assembled via a BOM) carry no on-hand
 *       row of their own, so the parent resolves as uncosted and passes. Unlike
 *       {@link NegativeStockGuard}, this guard does not explode the recipe and does not compare the
 *       rolled-up component cost — selling a kit under cost is NOT caught. Extending the guard to
 *       the exploded components is the natural follow-up if that becomes a real gap.</li>
 *   <li><b>A line with no positive base quantity</b> has no unit price to derive and is skipped
 *       outright (unreachable today: {@code sales_invoice_lines.quantity} is CHECK &gt; 0).</li>
 * </ul>
 */
@Component
public class BelowCostGuard {

    private static final Logger log = LoggerFactory.getLogger(BelowCostGuard.class);

    /** Names listed in a rejection before it collapses into "…and N more". */
    private static final int MAX_NAMED_PRODUCTS = 3;

    /** Scale for the derived net-unit-price; matches the cost scale used by StockLeafCostResolver. */
    private static final int PRICE_SCALE = 4;

    private static final String TARGET_TYPE   = "sales_invoices";
    private static final String DETAIL_ACTION = "action";
    private static final String DETAIL_REASON = "reason";
    private static final String DETAIL_ITEMS  = "products";

    private final SalesSettingsRepository settings;
    private final LeafCostResolver costs;
    private final PermissionResolver permissionResolver;
    private final AuditService audit;

    public BelowCostGuard(SalesSettingsRepository settings,
                          LeafCostResolver costs,
                          PermissionResolver permissionResolver,
                          AuditService audit) {
        this.settings           = settings;
        this.costs              = costs;
        this.permissionResolver = permissionResolver;
        this.audit              = audit;
    }

    /**
     * One priced invoice line, as the guard needs to see it.
     *
     * @param productId   the line product's internal id — the cost lookup key
     * @param productName for the user-facing message only (never an id — error-hygiene rule)
     * @param netAmount   the line's NET amount as recomputed by {@link InvoiceTotalsCalculator}
     *                    (VAT stripped for an inclusive line; discounts already applied)
     * @param qtyInBase   the line quantity in the product's base unit
     */
    public record PricedLine(Long productId, String productName,
                             BigDecimal netAmount, BigDecimal qtyInBase) {}

    /**
     * Applies the company's below-cost policy to a finalising invoice's lines.
     *
     * <p>Throws a friendly {@link ConflictException} (no ids/uids/costs/internal codes) when the
     * policy rejects the sale. Costs are never quoted back to the caller: a till operator has no
     * business reading margin off an error toast.
     *
     * @param companyId        tenant
     * @param branchId         branch whose moving-average cost applies (ADR-0020 is per-branch)
     * @param invoiceId        for the audit trail only
     * @param invoiceUid       for the audit trail only
     * @param lines            the invoice's lines, AFTER {@code InvoiceTotalsCalculator.recompute}
     * @param approvalSupplied caller ticked "sell below cost" on the request (APPROVE mode only)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void assertNotBelowCost(Long companyId, Long branchId,
                                   Long invoiceId, String invoiceUid,
                                   List<PricedLine> lines, boolean approvalSupplied) {
        if (lines == null || lines.isEmpty()) {
            return;
        }

        BelowCostAction action = settings.findByCompanyId(companyId)
                .map(SalesSettings::getBelowCostAction)
                // No Sales Settings row → OFF. This must be the SAME value the Sales Settings API
                // reports for that state (it returns a transient default row, whose field initialiser
                // is OFF) — the negative-stock setting shipped broken precisely because the two sides
                // disagreed about a missing row, and the screen ended up lying to the client.
                // NegativeStockSettingCrossLayerContractTest's sibling pins this agreement.
                .orElse(BelowCostAction.OFF);
        if (action == BelowCostAction.OFF) {
            return;
        }

        // ONE call for the whole invoice, keyed on the DISTINCT product ids — never one per line.
        // (The resolver behind it is still per-product internally; see the class javadoc.)
        Map<Long, BigDecimal> avgCosts = costs.avgCosts(companyId, branchId, distinctProductIds(lines));

        List<String> belowCost = new ArrayList<>();
        List<String> uncosted  = new ArrayList<>();
        classify(lines, avgCosts, belowCost, uncosted);

        if (!uncosted.isEmpty()) {
            log.warn("Below-cost policy {} could not check {} line(s) on invoice {} (company {}, "
                            + "branch {}): no established average cost.",
                    action, uncosted.size(), invoiceUid, companyId, branchId);
            audit.record(AuditEvent.of(AuditActions.SALES_BELOW_COST_WARNING, TARGET_TYPE,
                            invoiceId, invoiceUid)
                    .detail(Map.of(DETAIL_ACTION, action.name(),
                                   DETAIL_REASON, "COST_UNKNOWN",
                                   DETAIL_ITEMS, String.join(", ", uncosted))));
        }
        if (belowCost.isEmpty()) {
            return;
        }

        switch (action) {
            case WARN -> {
                log.warn("Below-cost sale allowed (WARN) on invoice {} (company {}): {} line(s) at "
                        + "or below cost.", invoiceUid, companyId, belowCost.size());
                audit.record(AuditEvent.of(AuditActions.SALES_BELOW_COST_WARNING, TARGET_TYPE,
                                invoiceId, invoiceUid)
                        .detail(Map.of(DETAIL_ACTION, action.name(),
                                       DETAIL_REASON, "AT_OR_BELOW_COST",
                                       DETAIL_ITEMS, String.join(", ", belowCost))));
            }
            case APPROVE -> approve(invoiceId, invoiceUid, companyId, belowCost, approvalSupplied);
            case BLOCK -> throw new ConflictException(blockedMessage(belowCost));
            default -> { /* OFF returned above */ }
        }
    }

    // -------------------------------------------------------------------------

    /**
     * APPROVE mode: the sale goes through only when the caller BOTH asked for the override on the
     * request AND actually holds {@code SALES.BELOW_COST.OVERRIDE} — a flag alone must never be
     * enough, or any till client could set it and the policy would be decorative.
     */
    private void approve(Long invoiceId, String invoiceUid, Long companyId,
                         List<String> belowCost, boolean approvalSupplied) {
        boolean hasOverride = permissionResolver.hasPermission(
                RequestContext.get(), "SALES.BELOW_COST.OVERRIDE", System.currentTimeMillis());
        if (!approvalSupplied || !hasOverride) {
            throw new ConflictException(needsApprovalMessage(belowCost));
        }
        log.warn("Below-cost sale approved on invoice {} (company {}): {} line(s) at or below cost.",
                invoiceUid, companyId, belowCost.size());
        audit.record(AuditEvent.of(AuditActions.SALES_BELOW_COST_OVERRIDE, TARGET_TYPE,
                        invoiceId, invoiceUid)
                .detail(Map.of(DETAIL_ACTION, BelowCostAction.APPROVE.name(),
                               DETAIL_ITEMS, String.join(", ", belowCost))));
    }

    /**
     * Splits the lines into "at or below cost" and "could not be checked", by product name.
     * A line with no positive base quantity has no price to compare and falls into neither.
     */
    private static void classify(List<PricedLine> lines, Map<Long, BigDecimal> avgCosts,
                                 List<String> belowCost, List<String> uncosted) {
        for (PricedLine line : lines) {
            BigDecimal netUnitPrice = netUnitPrice(line);
            if (netUnitPrice == null) {
                continue;   // no quantity to price against — nothing to compare
            }
            BigDecimal unitCost = avgCosts.get(line.productId());
            if (unitCost == null || unitCost.compareTo(BigDecimal.ZERO) <= 0) {
                uncosted.add(line.productName());        // unknown cost never rejects (owner decision)
            } else if (netUnitPrice.compareTo(unitCost) <= 0) {   // "equal or less than cost"
                belowCost.add(line.productName());
            }
        }
    }

    private static List<Long> distinctProductIds(List<PricedLine> lines) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PricedLine line : lines) {
            if (line.productId() != null) {
                ids.add(line.productId());
            }
        }
        return List.copyOf(ids);
    }

    /**
     * The line's net price for ONE base unit — {@code netAmount / qtyInBase}. Returns {@code null}
     * when there is no positive quantity to divide by (nothing meaningful to compare).
     */
    private static BigDecimal netUnitPrice(PricedLine line) {
        BigDecimal qty = line.qtyInBase();
        BigDecimal net = line.netAmount();
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        BigDecimal safeNet = net == null ? BigDecimal.ZERO : net;
        return safeNet.divide(qty, PRICE_SCALE, RoundingMode.HALF_UP);
    }

    private static String needsApprovalMessage(List<String> products) {
        return subject(products) + " priced at or below cost. "
                + "A supervisor needs to approve this sale before it can be completed.";
    }

    private static String blockedMessage(List<String> products) {
        return subject(products) + " priced at or below cost, so this sale cannot be completed. "
                + "Raise the price, or ask a supervisor to review the pricing policy.";
    }

    /**
     * Names the offending products for the user and agrees the verb with how many there are —
     * capped so a big basket cannot produce a wall of text. Product names only: no ids, and never
     * the cost figure itself (a till operator has no business reading margin off an error message).
     */
    private static String subject(List<String> products) {
        List<String> distinct = List.copyOf(new LinkedHashSet<>(products));
        if (distinct.size() == 1) {
            return distinct.get(0) + " is";
        }
        if (distinct.size() <= MAX_NAMED_PRODUCTS) {
            return String.join(", ", distinct) + " are";
        }
        int hidden = distinct.size() - MAX_NAMED_PRODUCTS;
        return String.join(", ", distinct.subList(0, MAX_NAMED_PRODUCTS))
                + " and " + hidden + " more item" + (hidden == 1 ? "" : "s") + " are";
    }
}
