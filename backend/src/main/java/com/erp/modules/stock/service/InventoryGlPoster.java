package com.erp.modules.stock.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.gl.service.GLPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds and posts GL drafts for all inventory valuation legs (ADR-0020 D-4).
 *
 * <p>Event-driven legs (receipt, sale, reversals) post via
 * {@link GLPostingSafeInvoker#postInNewTx} (REQUIRES_NEW, null-on-anomaly — never poisons the
 * dispatch TX). Human-act legs (opening valuation, adjustment) post via
 * {@link GLPostingService#post} directly — a missing config MUST fail the operator's command
 * (BR-INV-12).
 *
 * <p>All amounts are base currency, HALF_UP (BR-INV-11). Accounts resolved via
 * {@link GLConfigResolver#resolve} (throws on missing/inactive — BR-GL-10 / BR-INV-12).
 */
@Component
public class InventoryGlPoster {

    private static final Logger log = LoggerFactory.getLogger(InventoryGlPoster.class);

    private final GLPostingSafeInvoker safeInvoker;
    private final GLPostingService     directPosting;
    private final GLConfigResolver     configResolver;

    public InventoryGlPoster(GLPostingSafeInvoker safeInvoker,
                              GLPostingService directPosting,
                              GLConfigResolver configResolver) {
        this.safeInvoker    = safeInvoker;
        this.directPosting  = directPosting;
        this.configResolver = configResolver;
    }

    // -------------------------------------------------------------------------
    // (a) Goods receipt: DR INVENTORY / CR GRNI (event-driven, REQUIRES_NEW)
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR GRNI (2150) for a goods receipt.
     * One journal per receipt, with one DR/CR leg pair per line (ADR-0020 D-4a).
     * Uses safe invoker — returns null on GL anomaly, never propagates.
     *
     * @param legs        per-line (lineUid, productCode, value) tuples
     * @param receiptUid  sourceRef
     * @param postingDate posting date
     * @param currency    base currency code
     * @param companyId   tenant
     * @param branchId    branch
     * @return posted JournalEntryDto uid, or null on anomaly
     */
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

            JournalEntryDto result = safeInvoker.postInNewTx(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: receipt GL post failed for company={} receipt={} — {}",
                    companyId, receiptUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (b) Sale COGS: DR COGS / CR INVENTORY (event-driven, REQUIRES_NEW)
    // -------------------------------------------------------------------------

    /**
     * Post DR COGS (5100) / CR INVENTORY (1300) for a sale.
     * One journal per SALE.FINALISED, one DR/CR leg pair per issued component (ADR-0020 D-4b).
     * Returns null on GL anomaly; never propagates.
     *
     * @param legs       per-component (productId, productCode, value) tuples
     * @param invoiceUid sourceRef
     */
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

            JournalEntryDto result = safeInvoker.postInNewTx(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: COGS GL post failed for company={} invoice={} — {}",
                    companyId, invoiceUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (d) Receipt reversal: DR GRNI / CR INVENTORY (event-driven, REQUIRES_NEW)
    // -------------------------------------------------------------------------

    /**
     * Post DR GRNI (2150) / CR INVENTORY (1300) for a receipt reversal at original cost.
     * (ADR-0020 D-5). Returns null on anomaly; never propagates.
     */
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

            JournalEntryDto result = safeInvoker.postInNewTx(draft);
            return result != null ? result.uid() : null;

        } catch (Exception ex) {
            log.warn("InventoryGlPoster: receipt reversal GL post failed for company={} receipt={} — {}",
                    companyId, receiptUid, ex.getMessage());
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // (d) Sale reversal (COGS reversal): DR INVENTORY / CR COGS (event-driven, REQUIRES_NEW)
    // -------------------------------------------------------------------------

    /**
     * Post DR INVENTORY (1300) / CR COGS (5100) for a sale void at original cost.
     * (ADR-0020 D-5). Returns null on anomaly; never propagates.
     */
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

            JournalEntryDto result = safeInvoker.postInNewTx(draft);
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
     */
    public JournalEntryDto postAdjustmentDirect(Long companyId, Long branchId,
                                                 LocalDate postingDate,
                                                 String movementUid, String currency,
                                                 BigDecimal value, boolean decrease,
                                                 Long postedBy) {
        ChartOfAccount inventoryAcct   = configResolver.resolve(companyId, GlConfigKey.INVENTORY);
        ChartOfAccount adjustmentAcct  = configResolver.resolve(companyId, GlConfigKey.STOCK_ADJUSTMENT);

        List<LineDraft> lines;
        if (decrease) {
            // DR STOCK_ADJUSTMENT / CR INVENTORY
            lines = List.of(
                    new LineDraft(adjustmentAcct.getId(),
                            value, BigDecimal.ZERO,
                            currency, "Stock adjustment — " + movementUid),
                    new LineDraft(inventoryAcct.getId(),
                            BigDecimal.ZERO, value,
                            currency, "Inventory adjustment — " + movementUid)
            );
        } else {
            // DR INVENTORY / CR STOCK_ADJUSTMENT
            lines = List.of(
                    new LineDraft(inventoryAcct.getId(),
                            value, BigDecimal.ZERO,
                            currency, "Inventory adjustment — " + movementUid),
                    new LineDraft(adjustmentAcct.getId(),
                            BigDecimal.ZERO, value,
                            currency, "Stock adjustment — " + movementUid)
            );
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, postingDate,
                "Stock adjustment " + movementUid,
                JournalSourceType.STOCK_ADJUSTMENT, movementUid,
                null, postedBy, lines);

        return directPosting.post(draft);
    }

    // -------------------------------------------------------------------------
    // Value-object legs
    // -------------------------------------------------------------------------

    /** Per receipt-line GL leg data. */
    public record ReceiptLeg(String grLineUid, String productCode, BigDecimal value) {}

    /** Per sale-component GL leg data. */
    public record CogsLeg(Long productId, String productCode, BigDecimal value) {}
}
