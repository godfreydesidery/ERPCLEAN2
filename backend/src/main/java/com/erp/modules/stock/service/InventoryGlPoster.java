package com.erp.modules.stock.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds and posts GL drafts for all inventory valuation legs (ADR-0020 D-4).
 *
 * <p><strong>FIX B (adversarial review) — GL config resolution inside REQUIRES_NEW:</strong>
 * Each event-driven post method ({@code postReceiptInNewTx}, {@code postCogsInNewTx},
 * {@code postReceiptReversalInNewTx}, {@code postSaleReversalInNewTx}) is itself annotated
 * {@code @Transactional(REQUIRES_NEW)} and performs {@code configResolver.resolve()} INSIDE
 * that boundary, followed immediately by the {@link GLPostingService#post} call, all wrapped in
 * a try/catch that returns {@code null} on any anomaly.
 *
 * <p>Why this matters: {@link GLConfigResolver} is {@code @Transactional(MANDATORY)} — if
 * resolution were called in the handler's outer TX and threw (missing/INACTIVE account), Spring's
 * TX interceptor would mark that outer TX rollback-only <em>before</em> the catch block ran,
 * silently rolling back the physical stock movement (quantity deduction / receipt). Moving the
 * resolve + build + post into one {@code REQUIRES_NEW} method means a GL-config failure rolls
 * back only that inner TX, leaving the handler's stock TX intact (the exact guarantee
 * {@link com.erp.modules.gl.service.GLPostingSafeInvoker} provides for the Sales module).
 *
 * <p>Human-act legs (opening valuation, adjustment) post via
 * {@link GLPostingService#post} directly — a missing config MUST fail the operator's command
 * (BR-INV-12). Those paths are NOT annotated REQUIRES_NEW.
 *
 * <p>All amounts are base currency, HALF_UP (BR-INV-11). Accounts resolved via
 * {@link GLConfigResolver#resolve} (throws on missing/inactive — BR-GL-10 / BR-INV-12).
 */
@Component
public class InventoryGlPoster {

    private static final Logger log = LoggerFactory.getLogger(InventoryGlPoster.class);

    private final GLPostingService  directPosting;
    private final GLConfigResolver  configResolver;

    public InventoryGlPoster(GLPostingService directPosting,
                              GLConfigResolver configResolver) {
        this.directPosting  = directPosting;
        this.configResolver = configResolver;
    }

    // -------------------------------------------------------------------------
    // (a) Goods receipt: DR INVENTORY / CR GRNI (event-driven, REQUIRES_NEW)
    //     FIX B: resolve + build + post all inside this REQUIRES_NEW boundary.
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR GRNI (2150) for a goods receipt.
     * One journal per receipt, with one DR/CR leg pair per line (ADR-0020 D-4a).
     * REQUIRES_NEW — returns null on GL anomaly, never propagates to the handler TX.
     *
     * @param legs        per-line (lineUid, productCode, value) tuples
     * @param receiptUid  sourceRef
     * @param postingDate posting date
     * @param currency    base currency code
     * @param companyId   tenant
     * @param branchId    branch
     * @return posted JournalEntry uid, or null on anomaly
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postReceiptInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                      String receiptUid, String currency,
                                      List<ReceiptLeg> legs) {
        try {
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
            ChartOfAccount grniAcct      = configResolver.resolve(companyId, GlConfigKey.GRNI);

            List<LineDraft> lines = new ArrayList<>();
            for (ReceiptLeg leg : legs) {
                lines.add(new LineDraft(inventoryAcct.getId(),
                        leg.value(), BigDecimal.ZERO,
                        currency, "GR line " + leg.grLineUid() + " — " + leg.productCode()));
                lines.add(new LineDraft(grniAcct.getId(),
                        BigDecimal.ZERO, leg.value(),
                        currency, "GRNI " + leg.grLineUid() + " — " + leg.productCode()));
            }

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "Goods receipt " + receiptUid,
                    JournalSourceType.STOCK_RECEIPT, receiptUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: receipt GL post failed for company={} receipt={} — {}",
                    companyId, receiptUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (b) Sale COGS: DR COGS / CR INVENTORY (event-driven, REQUIRES_NEW)
    //     FIX B: resolve + build + post all inside this REQUIRES_NEW boundary.
    // -------------------------------------------------------------------------

    /**
     * Post DR COGS (5100) / CR INVENTORY (1300) for a sale.
     * One journal per SALE.FINALISED, one DR/CR leg pair per issued component (ADR-0020 D-4b).
     * REQUIRES_NEW — returns null on GL anomaly; never propagates.
     *
     * @param legs       per-component (productId, productCode, value) tuples
     * @param invoiceUid sourceRef
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postCogsInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                   String invoiceUid, String currency,
                                   List<CogsLeg> legs) {
        if (legs.isEmpty()) return null;
        try {
            ChartOfAccount cogsAcct      = configResolver.resolve(companyId, GlConfigKey.COGS);
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);

            List<LineDraft> lines = new ArrayList<>();
            for (CogsLeg leg : legs) {
                lines.add(new LineDraft(cogsAcct.getId(),
                        leg.value(), BigDecimal.ZERO,
                        currency, "COGS " + leg.productCode() + " — " + invoiceUid));
                lines.add(new LineDraft(inventoryAcct.getId(),
                        BigDecimal.ZERO, leg.value(),
                        currency, "Inventory out " + leg.productCode() + " — " + invoiceUid));
            }

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "COGS — sale " + invoiceUid,
                    JournalSourceType.COGS, invoiceUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: COGS GL post failed for company={} invoice={} — {}",
                    companyId, invoiceUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (d) Receipt reversal: DR GRNI / CR INVENTORY (event-driven, REQUIRES_NEW)
    //     FIX B: resolve + build + post all inside this REQUIRES_NEW boundary.
    // -------------------------------------------------------------------------

    /**
     * Post DR GRNI (2150) / CR INVENTORY (1300) for a receipt reversal at original cost.
     * (ADR-0020 D-5). REQUIRES_NEW — returns null on anomaly; never propagates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postReceiptReversalInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                              String receiptUid, String currency,
                                              BigDecimal totalOriginalValue) {
        try {
            ChartOfAccount grniAcct      = configResolver.resolve(companyId, GlConfigKey.GRNI);
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);

            List<LineDraft> lines = List.of(
                    new LineDraft(grniAcct.getId(),
                            totalOriginalValue, BigDecimal.ZERO,
                            currency, "GR reversal GRNI — " + receiptUid),
                    new LineDraft(inventoryAcct.getId(),
                            BigDecimal.ZERO, totalOriginalValue,
                            currency, "GR reversal Inventory — " + receiptUid)
            );

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "GR reversal " + receiptUid,
                    JournalSourceType.STOCK_RECEIPT, receiptUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: receipt reversal GL post failed for company={} receipt={} — {}",
                    companyId, receiptUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (d) Sale reversal (COGS reversal): DR INVENTORY / CR COGS (event-driven, REQUIRES_NEW)
    //     FIX B: resolve + build + post all inside this REQUIRES_NEW boundary.
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR COGS (5100) for a sale void at original cost.
     * (ADR-0020 D-5). REQUIRES_NEW — returns null on anomaly; never propagates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postSaleReversalInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                           String invoiceUid, String currency,
                                           BigDecimal totalOriginalValue) {
        try {
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
            ChartOfAccount cogsAcct      = configResolver.resolve(companyId, GlConfigKey.COGS);

            List<LineDraft> lines = List.of(
                    new LineDraft(inventoryAcct.getId(),
                            totalOriginalValue, BigDecimal.ZERO,
                            currency, "Sale reversal Inventory — " + invoiceUid),
                    new LineDraft(cogsAcct.getId(),
                            BigDecimal.ZERO, totalOriginalValue,
                            currency, "Sale reversal COGS — " + invoiceUid)
            );

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "COGS reversal — void " + invoiceUid,
                    JournalSourceType.COGS, invoiceUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: sale reversal GL post failed for company={} invoice={} — {}",
                    companyId, invoiceUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (e) Opening valuation: DR INVENTORY / CR OPENING_BALANCE_EQUITY (direct, operator TX)
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR OPENING_BALANCE_EQUITY (3100) for opening valuation.
     * Direct post — a missing config MUST fail the operator's command (BR-INV-12).
     */
    public JournalEntryDto postOpeningValuationDirect(Long companyId, Long branchId,
                                                       LocalDate postingDate,
                                                       String stockOnHandUid, String currency,
                                                       BigDecimal openingValue, Long postedBy) {
        ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
        ChartOfAccount obeAcct       = configResolver.resolve(companyId, GlConfigKey.OPENING_BALANCE_EQUITY);

        List<LineDraft> lines = List.of(
                new LineDraft(inventoryAcct.getId(),
                        openingValue, BigDecimal.ZERO,
                        currency, "Opening inventory — " + stockOnHandUid),
                new LineDraft(obeAcct.getId(),
                        BigDecimal.ZERO, openingValue,
                        currency, "Opening balance equity — " + stockOnHandUid)
        );

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, postingDate,
                "Opening inventory valuation — " + stockOnHandUid,
                JournalSourceType.OPENING_INVENTORY, stockOnHandUid,
                null, postedBy, lines);

        return directPosting.post(draft);
    }

    // -------------------------------------------------------------------------
    // (f) Adjustment revaluation: DR STOCK_ADJUSTMENT / CR INVENTORY or reverse (direct)
    // -------------------------------------------------------------------------

    /**
     * Post DR STOCK_ADJUSTMENT (5160) / CR INVENTORY (1300) for an adjustment decrease,
     * or DR INVENTORY / CR STOCK_ADJUSTMENT for an increase.
     * Direct post — a missing config MUST fail the operator's command (BR-INV-12).
     *
     * @param cmd bundles the adjustment-specific posting parameters (movementUid, currency,
     *            value, decrease flag, postedBy) to keep the method under the 7-param limit.
     */
    public JournalEntryDto postAdjustmentDirect(Long companyId, Long branchId,
                                                 LocalDate postingDate,
                                                 AdjustmentPostCmd cmd) {
        ChartOfAccount inventoryAcct   = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
        ChartOfAccount adjustmentAcct  = configResolver.resolve(companyId, GlConfigKey.STOCK_ADJUSTMENT);

        // ADR-0025 D-6: only the P&L-relevant leg (STOCK_ADJUSTMENT expense) carries the
        // dimension tag; the balance-sheet INVENTORY control leg posts untagged (D-6 decision).
        List<LineDraft> lines;
        if (cmd.decrease()) {
            // DR STOCK_ADJUSTMENT (expense — tagged) / CR INVENTORY (BS — untagged)
            lines = List.of(
                    new LineDraft(adjustmentAcct.getId(),
                            cmd.value(), BigDecimal.ZERO,
                            cmd.currency(), "Stock adjustment — " + cmd.movementUid(),
                            cmd.costCentreValueId(), cmd.departmentValueId(), null, null),
                    new LineDraft(inventoryAcct.getId(),
                            BigDecimal.ZERO, cmd.value(),
                            cmd.currency(), "Inventory adjustment — " + cmd.movementUid())
            );
        } else {
            // DR INVENTORY (BS — untagged) / CR STOCK_ADJUSTMENT (expense — tagged)
            lines = List.of(
                    new LineDraft(inventoryAcct.getId(),
                            cmd.value(), BigDecimal.ZERO,
                            cmd.currency(), "Inventory adjustment — " + cmd.movementUid()),
                    new LineDraft(adjustmentAcct.getId(),
                            BigDecimal.ZERO, cmd.value(),
                            cmd.currency(), "Stock adjustment — " + cmd.movementUid(),
                            cmd.costCentreValueId(), cmd.departmentValueId(), null, null)
            );
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, postingDate,
                "Stock adjustment " + cmd.movementUid(),
                JournalSourceType.STOCK_ADJUSTMENT, cmd.movementUid(),
                null, cmd.postedBy(), lines);

        return directPosting.post(draft);
    }

    // -------------------------------------------------------------------------
    // Value-object legs / commands
    // -------------------------------------------------------------------------

    /** Per receipt-line GL leg data. */
    public record ReceiptLeg(String grLineUid, String productCode, BigDecimal value) {}

    /** Per sale-component GL leg data. */
    public record CogsLeg(Long productId, String productCode, BigDecimal value) {}

    /**
     * Parameters for {@link #postAdjustmentDirect} — bundles the adjustment-specific
     * fields so the method stays under the 7-parameter Sonar limit (java:S107).
     *
     * <p>ADR-0025 D-6: {@code costCentreValueId} and {@code departmentValueId} are optional
     * dimension tag ids for the expense leg (already resolved by the caller via
     * {@code DimensionResolver}). Null = untagged (NFR-CC-01).
     * The 5-arg constructor defaults them to null for backward compatibility.
     */
    public record AdjustmentPostCmd(String movementUid, String currency,
                                    BigDecimal value, boolean decrease, Long postedBy,
                                    Long costCentreValueId, Long departmentValueId) {
        /** Convenience 5-arg constructor — existing callers unchanged (NFR-CC-01). */
        public AdjustmentPostCmd(String movementUid, String currency,
                                 BigDecimal value, boolean decrease, Long postedBy) {
            this(movementUid, currency, value, decrease, postedBy, null, null);
        }
    }
}
