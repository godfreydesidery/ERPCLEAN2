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
 *
 * <p><strong>FOLLOW-001:</strong> all journal DESCRIPTION / line memo texts use human-readable
 * document numbers (GRN, invoice, delivery numbers) passed in by the caller. The {@code sourceRef}
 * arg still carries the uid (machine linkage, not displayed as text). Raw 26-char ULIDs are never
 * embedded in user-visible memo text.
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
     * @param receiptUid  sourceRef (machine linkage — kept as uid per ADR)
     * @param receiptNumber human-readable document number for memo text (FOLLOW-001)
     * @param postingDate posting date
     * @param currency    base currency code
     * @param companyId   tenant
     * @param branchId    branch
     * @return posted JournalEntry uid, or null on anomaly
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postReceiptInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                      String receiptUid, String receiptNumber, String currency,
                                      List<ReceiptLeg> legs) {
        String docLabel = receiptNumber != null ? receiptNumber : receiptUid;
        try {
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
            ChartOfAccount grniAcct      = configResolver.resolve(companyId, GlConfigKey.GRNI);

            List<LineDraft> lines = new ArrayList<>();
            for (ReceiptLeg leg : legs) {
                // FOLLOW-001: human-readable memo only (product code + GRN number) — never a uid.
                // Mirrors the COGS leg format ("COGS <productCode> — <docLabel>").
                lines.add(new LineDraft(inventoryAcct.getId(),
                        leg.value(), BigDecimal.ZERO,
                        currency, "GR " + leg.productCode() + " — " + docLabel));
                lines.add(new LineDraft(grniAcct.getId(),
                        BigDecimal.ZERO, leg.value(),
                        currency, "GRNI " + leg.productCode() + " — " + docLabel));
            }

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "Goods receipt " + docLabel,
                    JournalSourceType.STOCK_RECEIPT, receiptUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: receipt GL post failed for company={} receipt={} — {}",
                    companyId, docLabel, ex.getMessage());
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
     * @param legs          per-component (productId, productCode, value) tuples
     * @param invoiceUid    sourceRef (machine linkage)
     * @param invoiceNumber human-readable invoice number for memo text (FOLLOW-001)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postCogsInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                   String invoiceUid, String invoiceNumber, String currency,
                                   List<CogsLeg> legs) {
        if (legs.isEmpty()) return null;
        String docLabel = invoiceNumber != null ? invoiceNumber : invoiceUid;
        try {
            ChartOfAccount cogsAcct      = configResolver.resolve(companyId, GlConfigKey.COGS);
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);

            List<LineDraft> lines = new ArrayList<>();
            for (CogsLeg leg : legs) {
                lines.add(new LineDraft(cogsAcct.getId(),
                        leg.value(), BigDecimal.ZERO,
                        currency, "COGS " + leg.productCode() + " — " + docLabel));
                lines.add(new LineDraft(inventoryAcct.getId(),
                        BigDecimal.ZERO, leg.value(),
                        currency, "Inventory out " + leg.productCode() + " — " + docLabel));
            }

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "COGS — sale " + docLabel,
                    JournalSourceType.COGS, invoiceUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: COGS GL post failed for company={} invoice={} — {}",
                    companyId, docLabel, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (c) Project material issue COGS: DR COGS (project-tagged) / CR INVENTORY
    //     (ADR-0033 D-5, REQUIRES_NEW)
    // -------------------------------------------------------------------------

    /**
     * Post DR COGS (5100, tagged with project) / CR INVENTORY (1300) for a project material issue.
     * Reuses the existing COGS / INVENTORY gl_config keys (ADR-0033 D-8 — no new GL config keys).
     * REQUIRES_NEW — returns null on GL anomaly; never propagates to caller TX.
     *
     * @param legs          per-line (productId, productCode, value, projectCostType) tuples
     * @param issueRef      issue document uid (sourceRef — machine linkage)
     * @param issueNumber   human-readable issue number for memo text (FOLLOW-001; nullable)
     * @param projectId     FK to projects(id) — stamped on the COGS leg
     * @param projectTaskId FK to project_tasks(id) — stamped on the COGS leg (nullable)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postCogsForProjectInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                             String issueRef, String issueNumber, String currency,
                                             Long projectId, Long projectTaskId,
                                             List<ProjectCogsLeg> legs) {
        if (legs.isEmpty()) return null;
        String docLabel = issueNumber != null ? issueNumber : issueRef;
        try {
            ChartOfAccount cogsAcct      = configResolver.resolve(companyId, GlConfigKey.COGS);
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);

            List<LineDraft> lines = new ArrayList<>();
            for (ProjectCogsLeg leg : legs) {
                // COGS leg: project-tagged (ADR-0033 D-1 — project_id / project_task_id scalars)
                lines.add(new LineDraft(cogsAcct.getId(),
                        leg.value(), BigDecimal.ZERO,
                        currency, "Project COGS " + leg.productCode() + " — " + docLabel,
                        null, null, null, null,
                        projectId, projectTaskId, leg.projectCostType()));
                // Inventory CR leg: untagged (balance-sheet leg, ADR-0025 D-6 pattern)
                lines.add(new LineDraft(inventoryAcct.getId(),
                        BigDecimal.ZERO, leg.value(),
                        currency, "Inventory out " + leg.productCode() + " — " + docLabel));
            }

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "Project material issue — " + docLabel,
                    JournalSourceType.COGS, issueRef,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: project COGS GL post failed for company={} issue={} — {}",
                    companyId, docLabel, ex.getMessage());
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
     *
     * @param receiptUid    sourceRef (machine linkage)
     * @param receiptNumber human-readable GRN number for memo text (FOLLOW-001)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postReceiptReversalInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                              String receiptUid, String receiptNumber,
                                              String currency,
                                              BigDecimal totalOriginalValue) {
        String docLabel = receiptNumber != null ? receiptNumber : receiptUid;
        try {
            ChartOfAccount grniAcct      = configResolver.resolve(companyId, GlConfigKey.GRNI);
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);

            List<LineDraft> lines = List.of(
                    new LineDraft(grniAcct.getId(),
                            totalOriginalValue, BigDecimal.ZERO,
                            currency, "GR reversal GRNI — " + docLabel),
                    new LineDraft(inventoryAcct.getId(),
                            BigDecimal.ZERO, totalOriginalValue,
                            currency, "GR reversal Inventory — " + docLabel)
            );

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "GR reversal " + docLabel,
                    JournalSourceType.STOCK_RECEIPT, receiptUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: receipt reversal GL post failed for company={} receipt={} — {}",
                    companyId, docLabel, ex.getMessage());
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
     *
     * @param invoiceUid    sourceRef (machine linkage)
     * @param invoiceNumber human-readable invoice number for memo text (FOLLOW-001)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postSaleReversalInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                           String invoiceUid, String invoiceNumber,
                                           String currency,
                                           BigDecimal totalOriginalValue) {
        String docLabel = invoiceNumber != null ? invoiceNumber : invoiceUid;
        try {
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
            ChartOfAccount cogsAcct      = configResolver.resolve(companyId, GlConfigKey.COGS);

            List<LineDraft> lines = List.of(
                    new LineDraft(inventoryAcct.getId(),
                            totalOriginalValue, BigDecimal.ZERO,
                            currency, "Sale reversal Inventory — " + docLabel),
                    new LineDraft(cogsAcct.getId(),
                            BigDecimal.ZERO, totalOriginalValue,
                            currency, "Sale reversal COGS — " + docLabel)
            );

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "COGS reversal — void " + docLabel,
                    JournalSourceType.COGS, invoiceUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: sale reversal GL post failed for company={} invoice={} — {}",
                    companyId, docLabel, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (e) Opening valuation: DR INVENTORY / CR OPENING_BALANCE_EQUITY (direct, operator TX)
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR OPENING_BALANCE_EQUITY (3100) for opening valuation.
     * Direct post — a missing config MUST fail the operator's command (BR-INV-12).
     *
     * <p>FOLLOW-001: the opening valuation has no human document number; the on-hand uid is a
     * stable machine reference for the stock_on_hand record and is kept as the sourceRef.
     * The memo text uses the uid as a system identifier (opening valuation is an admin-only
     * operation not surfaced on the /admin/gl/journals user view in normal workflow).
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
     * @param cmd bundles the adjustment-specific posting parameters to keep the method under
     *            the 7-param limit.
     */
    public JournalEntryDto postAdjustmentDirect(Long companyId, Long branchId,
                                                 LocalDate postingDate,
                                                 AdjustmentPostCmd cmd) {
        ChartOfAccount inventoryAcct   = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
        ChartOfAccount adjustmentAcct  = configResolver.resolve(companyId, GlConfigKey.STOCK_ADJUSTMENT);

        // FOLLOW-001: build a human-readable memo; never embed the raw ULID (movementUid).
        // Use productCode + reasonCode as the descriptor — always available from the caller.
        String memoDescriptor = buildAdjustmentDescriptor(cmd);

        // ADR-0025 D-6: only the P&L-relevant leg (STOCK_ADJUSTMENT expense) carries the
        // dimension tag; the balance-sheet INVENTORY control leg posts untagged (D-6 decision).
        List<LineDraft> lines;
        if (cmd.decrease()) {
            // DR STOCK_ADJUSTMENT (expense — tagged) / CR INVENTORY (BS — untagged)
            lines = List.of(
                    new LineDraft(adjustmentAcct.getId(),
                            cmd.value(), BigDecimal.ZERO,
                            cmd.currency(), "Stock adjustment — " + memoDescriptor,
                            cmd.costCentreValueId(), cmd.departmentValueId(), null, null),
                    new LineDraft(inventoryAcct.getId(),
                            BigDecimal.ZERO, cmd.value(),
                            cmd.currency(), "Inventory adjustment — " + memoDescriptor)
            );
        } else {
            // DR INVENTORY (BS — untagged) / CR STOCK_ADJUSTMENT (expense — tagged)
            lines = List.of(
                    new LineDraft(inventoryAcct.getId(),
                            cmd.value(), BigDecimal.ZERO,
                            cmd.currency(), "Inventory adjustment — " + memoDescriptor),
                    new LineDraft(adjustmentAcct.getId(),
                            BigDecimal.ZERO, cmd.value(),
                            cmd.currency(), "Stock adjustment — " + memoDescriptor,
                            cmd.costCentreValueId(), cmd.departmentValueId(), null, null)
            );
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, postingDate,
                "Stock adjustment " + memoDescriptor,
                JournalSourceType.STOCK_ADJUSTMENT, cmd.sourceRef(),
                null, cmd.postedBy(), lines);

        return directPosting.post(draft);
    }

    /** Build human-readable descriptor for the adjustment GL memo (FOLLOW-001). */
    private static String buildAdjustmentDescriptor(AdjustmentPostCmd cmd) {
        String base = cmd.productCode() != null ? cmd.productCode() : "UNKNOWN";
        if (cmd.reasonCode() != null && !cmd.reasonCode().isBlank()) {
            return base + "/" + cmd.reasonCode();
        }
        return base;
    }

    // -------------------------------------------------------------------------
    // (g) Landed cost: DR INVENTORY / CR LANDED_COST_CLEARING (event-driven, REQUIRES_NEW)
    //     ADR-0027 D-5 / D-9. GRNI-bridge pattern reused for LANDED_COST_CLEARING (2160).
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR LANDED_COST_CLEARING (2160) for the total landed-cost amount.
     * One journal per landed cost confirm. REQUIRES_NEW — returns null on anomaly; never propagates.
     *
     * @param landedCostUid    sourceRef (machine linkage — landed_cost.uid)
     * @param landedCostNumber human-readable LC number for memo text (FOLLOW-001)
     * @param totalAmount      sum of all charge allocations
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postLandedCostInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                         String landedCostUid, String landedCostNumber,
                                         String currency,
                                         BigDecimal totalAmount) {
        String docLabel = landedCostNumber != null ? landedCostNumber : landedCostUid;
        try {
            ChartOfAccount inventoryAcct     = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
            ChartOfAccount landedClearAcct   = configResolver.resolve(companyId, GlConfigKey.LANDED_COST_CLEARING);

            List<LineDraft> lines = List.of(
                    new LineDraft(inventoryAcct.getId(),
                            totalAmount, BigDecimal.ZERO,
                            currency, "Landed cost — " + docLabel),
                    new LineDraft(landedClearAcct.getId(),
                            BigDecimal.ZERO, totalAmount,
                            currency, "Landed cost clearing — " + docLabel)
            );

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "Landed cost " + docLabel,
                    JournalSourceType.LANDED_COST, landedCostUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: landed cost GL post failed for company={} landedCost={} — {}",
                    companyId, docLabel, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (h) Purchase return: DR GRNI / CR INVENTORY (event-driven, REQUIRES_NEW)
    //     ADR-0027 D-7. Symmetric reverse of receipt: stock goes back to supplier.
    // -------------------------------------------------------------------------

    /**
     * Post DR GRNI (2150) / CR INVENTORY (1300) for a purchase return at original receipt cost.
     * One journal per confirmed purchase return. REQUIRES_NEW — returns null on anomaly; never propagates.
     *
     * @param returnUid        sourceRef (machine linkage — purchase_return.uid)
     * @param returnNumber     human-readable return number for memo text (FOLLOW-001)
     * @param totalReturnValue total value being returned (sum of line_value_amount from return lines)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String postPurchaseReturnInNewTx(Long companyId, Long branchId, LocalDate postingDate,
                                             String returnUid, String returnNumber,
                                             String currency,
                                             BigDecimal totalReturnValue) {
        String docLabel = returnNumber != null ? returnNumber : returnUid;
        try {
            ChartOfAccount grniAcct      = configResolver.resolve(companyId, GlConfigKey.GRNI);
            ChartOfAccount inventoryAcct = configResolver.resolve(companyId, GlConfigKey.INVENTORY);

            List<LineDraft> lines = List.of(
                    new LineDraft(grniAcct.getId(),
                            totalReturnValue, BigDecimal.ZERO,
                            currency, "Purchase return GRNI — " + docLabel),
                    new LineDraft(inventoryAcct.getId(),
                            BigDecimal.ZERO, totalReturnValue,
                            currency, "Purchase return Inventory — " + docLabel)
            );

            JournalEntryDraft draft = new JournalEntryDraft(
                    companyId, branchId, postingDate,
                    "Purchase return " + docLabel,
                    JournalSourceType.PURCHASE_RETURN, returnUid,
                    null, null, lines);

            JournalEntryDto result = directPosting.post(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: purchase return GL post failed for company={} return={} — {}",
                    companyId, docLabel, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Value-object legs / commands
    // -------------------------------------------------------------------------

    /** Per receipt-line GL leg data. */
    public record ReceiptLeg(String grLineUid, String productCode, BigDecimal value) {}

    /** Per sale-component GL leg data. */
    public record CogsLeg(Long productId, String productCode, BigDecimal value) {}

    /** Per project-issue GL leg data (ADR-0033 D-5). */
    public record ProjectCogsLeg(Long productId, String productCode, BigDecimal value,
                                 String projectCostType) {}

    /**
     * Parameters for {@link #postAdjustmentDirect} — bundles the adjustment-specific
     * fields so the method stays under the 7-parameter Sonar limit (java:S107).
     *
     * <p>ADR-0025 D-6: {@code costCentreValueId} and {@code departmentValueId} are optional
     * dimension tag ids for the expense leg (already resolved by the caller via
     * {@code DimensionResolver}). Null = untagged (NFR-CC-01).
     *
     * <p>FOLLOW-001: {@code productCode} and {@code reasonCode} are used for human-readable memo
     * text; {@code sourceRef} is the machine uid for sourceRef linkage (never shown in memo text).
     * The legacy 5-arg constructor defaults productCode, reasonCode, and sourceRef to null for
     * backward compatibility (existing callers are migrated to the named constructor).
     */
    public record AdjustmentPostCmd(
            /** Machine reference for JournalEntry.sourceRef — uid, never shown in memo text. */
            String sourceRef,
            String currency,
            BigDecimal value,
            boolean decrease,
            Long postedBy,
            Long costCentreValueId,
            Long departmentValueId,
            /** Human-readable product code for memo text (e.g. "RICE-5KG"). FOLLOW-001. */
            String productCode,
            /** Reason code for memo text (e.g. "DAMAGE", "COUNT_CORRECTION"). FOLLOW-001. */
            String reasonCode
    ) {
        /** Convenience 7-arg constructor — dimension-tagged, no productCode/reasonCode. */
        public AdjustmentPostCmd(String sourceRef, String currency,
                                 BigDecimal value, boolean decrease, Long postedBy,
                                 Long costCentreValueId, Long departmentValueId) {
            this(sourceRef, currency, value, decrease, postedBy,
                    costCentreValueId, departmentValueId, null, null);
        }

        /** Convenience 5-arg constructor — backward compat, no dimensions / no memo info. */
        public AdjustmentPostCmd(String sourceRef, String currency,
                                 BigDecimal value, boolean decrease, Long postedBy) {
            this(sourceRef, currency, value, decrease, postedBy, null, null, null, null);
        }

        /**
         * Legacy alias: old callers used field name {@code movementUid} for the sourceRef.
         * @deprecated Use {@link #sourceRef()} — kept for zero-risk migration; will be removed.
         */
        @Deprecated
        public String movementUid() { return sourceRef; }
    }
}
