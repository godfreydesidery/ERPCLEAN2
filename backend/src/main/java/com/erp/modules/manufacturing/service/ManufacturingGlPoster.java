package com.erp.modules.manufacturing.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.manufacturing.domain.entity.WorkOrder;
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
 * Builds and posts the WIP/Inventory/FG/labour-overhead/variance GL drafts
 * via {@link GLPostingService} (ADR-0035 D-4).
 *
 * <p>Synchronous human-act posture: a missing gl_config / closed period fails
 * the operator's command (not the REQUIRES_NEW safe-invoker path).
 *
 * <p>The P&L-relevant legs (labour/overhead clearing, variance) carry the WO's
 * optional {@code costCentreValueId} in the {@link LineDraft} dimension slot (D-9).
 */
@Component
public class ManufacturingGlPoster {

    private static final Logger log = LoggerFactory.getLogger(ManufacturingGlPoster.class);
    private static final String BASE_CCY = "TZS"; // base currency — ADR-0005

    private final GLPostingService glPosting;
    private final GLConfigResolver glResolver;

    public ManufacturingGlPoster(GLPostingService glPosting, GLConfigResolver glResolver) {
        this.glPosting  = glPosting;
        this.glResolver = glResolver;
    }

    /**
     * (a) Component issue: DR WIP / CR Inventory per costed leaf (D-4a).
     * One journal per issue event, one DR-WIP / CR-Inventory leg pair per costed leaf.
     *
     * @param wo            the work order
     * @param leafMemos     list of (componentCode, issuedValue) pairs — only costed leaves
     * @param postingDate   business date
     * @param postedBy      operator id
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postComponentIssue(WorkOrder wo,
                                    List<ComponentCostLeg> leafMemos,
                                    LocalDate postingDate,
                                    Long postedBy) {
        if (leafMemos.isEmpty()) {
            log.warn("ManufacturingGlPoster.postComponentIssue: no costed leaves for WO {} — no journal posted",
                     wo.getWoNumber());
            return;
        }
        Long wipAccountId  = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();
        Long invAccountId  = glResolver.resolve(wo.getCompanyId(), GlConfigKey.INVENTORY).getId();

        List<LineDraft> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ComponentCostLeg leg : leafMemos) {
            total = total.add(leg.value());
            lines.add(new LineDraft(invAccountId, BigDecimal.ZERO, leg.value(),
                                    BASE_CCY, leg.componentCode() + " | " + wo.getWoNumber()));
        }
        lines.add(0, new LineDraft(wipAccountId, total, BigDecimal.ZERO,
                                   BASE_CCY, "WIP debit | " + wo.getWoNumber()));

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " component issue",
                JournalSourceType.PRODUCTION_ISSUE, wo.getUid(),
                null, postedBy, lines));
    }

    /**
     * (b) Applied labour/overhead: DR WIP / CR Labour_Applied / CR Overhead_Applied (D-4b).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postLabourOverhead(WorkOrder wo,
                                    BigDecimal labourAmount, BigDecimal overheadAmount,
                                    LocalDate postingDate, Long postedBy) {
        BigDecimal totalDebit = labourAmount.add(overheadAmount);
        if (totalDebit.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        Long wipAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();

        List<LineDraft> lines = new ArrayList<>();
        lines.add(new LineDraft(wipAccountId, totalDebit, BigDecimal.ZERO,
                                BASE_CCY, "WIP labour/overhead | " + wo.getWoNumber(),
                                null, null, null, null));

        if (labourAmount.compareTo(BigDecimal.ZERO) > 0) {
            Long labourAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.LABOUR_APPLIED).getId();
            lines.add(new LineDraft(labourAccountId, BigDecimal.ZERO, labourAmount,
                                    BASE_CCY, "Labour applied | " + wo.getWoNumber(),
                                    wo.getCostCentreValueId(), null, null, null));
        }
        if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
            Long overheadAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.OVERHEAD_APPLIED).getId();
            lines.add(new LineDraft(overheadAccountId, BigDecimal.ZERO, overheadAmount,
                                    BASE_CCY, "Overhead applied | " + wo.getWoNumber(),
                                    wo.getCostCentreValueId(), null, null, null));
        }

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " labour/overhead applied",
                JournalSourceType.PRODUCTION_LABOUR, wo.getUid(),
                null, postedBy, lines));
    }

    /**
     * (c) Finished-goods receipt: DR Finished_Goods / CR WIP at relieved value (D-4c).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postFinishedGoodsReceipt(WorkOrder wo,
                                          BigDecimal relievedValue,
                                          LocalDate postingDate, Long postedBy) {
        Long fgAccountId  = glResolver.resolve(wo.getCompanyId(), GlConfigKey.FINISHED_GOODS).getId();
        Long wipAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " finished-goods receipt",
                JournalSourceType.PRODUCTION_RECEIPT, wo.getUid(),
                null, postedBy,
                List.of(
                    new LineDraft(fgAccountId,  relievedValue, BigDecimal.ZERO, BASE_CCY,
                                  wo.getFinishedProductCode() + " | " + wo.getWoNumber()),
                    new LineDraft(wipAccountId, BigDecimal.ZERO, relievedValue, BASE_CCY,
                                  "WIP relief | " + wo.getWoNumber())
                )));
    }

    /**
     * (d) Close variance clear: DR Variance / CR WIP (or reversed if residual < 0) (D-4d).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postVarianceClear(WorkOrder wo,
                                   BigDecimal residual,
                                   LocalDate postingDate, Long postedBy) {
        if (residual.compareTo(BigDecimal.ZERO) == 0) {
            return; // no residual — no journal needed
        }
        Long varAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.MANUFACTURING_VARIANCE).getId();
        Long wipAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();

        BigDecimal absResidual = residual.abs();
        boolean underRelieved = residual.compareTo(BigDecimal.ZERO) > 0;

        LineDraft varLeg = underRelieved
            ? new LineDraft(varAccountId, absResidual, BigDecimal.ZERO, BASE_CCY,
                            "Mfg variance | " + wo.getWoNumber(),
                            wo.getCostCentreValueId(), null, null, null)
            : new LineDraft(varAccountId, BigDecimal.ZERO, absResidual, BASE_CCY,
                            "Mfg variance (credit) | " + wo.getWoNumber(),
                            wo.getCostCentreValueId(), null, null, null);

        LineDraft wipLeg = underRelieved
            ? new LineDraft(wipAccountId, BigDecimal.ZERO, absResidual, BASE_CCY,
                            "WIP clear | " + wo.getWoNumber())
            : new LineDraft(wipAccountId, absResidual, BigDecimal.ZERO, BASE_CCY,
                            "WIP clear (negative residual) | " + wo.getWoNumber());

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " WIP variance clear at close",
                JournalSourceType.PRODUCTION_VARIANCE, wo.getUid(),
                null, postedBy, List.of(varLeg, wipLeg)));
    }

    /**
     * (e-1) Cancel: reverse a component-issue journal entry (D-4e).
     * Posts DR Inventory / CR WIP at original value.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postCancelIssueReversal(WorkOrder wo,
                                         List<ComponentCostLeg> leafMemos,
                                         LocalDate postingDate, Long postedBy) {
        if (leafMemos.isEmpty()) return;
        Long wipAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();
        Long invAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.INVENTORY).getId();

        List<LineDraft> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (ComponentCostLeg leg : leafMemos) {
            total = total.add(leg.value());
            lines.add(new LineDraft(invAccountId, leg.value(), BigDecimal.ZERO,
                                    BASE_CCY, "Reversal: " + leg.componentCode() + " | " + wo.getWoNumber()));
        }
        lines.add(new LineDraft(wipAccountId, BigDecimal.ZERO, total,
                                BASE_CCY, "Reversal: WIP | " + wo.getWoNumber()));

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " cancel: component-issue reversal",
                JournalSourceType.PRODUCTION_ISSUE, wo.getUid(),
                null, postedBy, lines));
    }

    /**
     * (e-2) Cancel: reverse a finished-goods receipt journal entry (D-4e).
     * Posts DR WIP / CR Finished_Goods at original value.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postCancelReceiptReversal(WorkOrder wo,
                                           BigDecimal relievedValue,
                                           LocalDate postingDate, Long postedBy) {
        Long fgAccountId  = glResolver.resolve(wo.getCompanyId(), GlConfigKey.FINISHED_GOODS).getId();
        Long wipAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " cancel: FG receipt reversal",
                JournalSourceType.PRODUCTION_RECEIPT, wo.getUid(),
                null, postedBy,
                List.of(
                    new LineDraft(wipAccountId, relievedValue, BigDecimal.ZERO, BASE_CCY,
                                  "WIP restore | " + wo.getWoNumber()),
                    new LineDraft(fgAccountId, BigDecimal.ZERO, relievedValue, BASE_CCY,
                                  "FG reversal | " + wo.getWoNumber())
                )));
    }

    /**
     * (e-3) Cancel: reverse applied labour/overhead (D-4e).
     * Posts DR Labour_Applied / DR Overhead_Applied / CR WIP.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void postCancelLabourOverheadReversal(WorkOrder wo,
                                                  BigDecimal labourAmount, BigDecimal overheadAmount,
                                                  LocalDate postingDate, Long postedBy) {
        BigDecimal totalCredit = labourAmount.add(overheadAmount);
        if (totalCredit.compareTo(BigDecimal.ZERO) <= 0) return;

        Long wipAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.WIP_INVENTORY).getId();
        List<LineDraft> lines = new ArrayList<>();
        lines.add(new LineDraft(wipAccountId, BigDecimal.ZERO, totalCredit,
                                BASE_CCY, "Cancel: WIP labour/overhead reversal | " + wo.getWoNumber(),
                                null, null, null, null));

        if (labourAmount.compareTo(BigDecimal.ZERO) > 0) {
            Long labourAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.LABOUR_APPLIED).getId();
            lines.add(new LineDraft(labourAccountId, labourAmount, BigDecimal.ZERO,
                                    BASE_CCY, "Cancel: labour applied reversal | " + wo.getWoNumber(),
                                    wo.getCostCentreValueId(), null, null, null));
        }
        if (overheadAmount.compareTo(BigDecimal.ZERO) > 0) {
            Long overheadAccountId = glResolver.resolve(wo.getCompanyId(), GlConfigKey.OVERHEAD_APPLIED).getId();
            lines.add(new LineDraft(overheadAccountId, overheadAmount, BigDecimal.ZERO,
                                    BASE_CCY, "Cancel: overhead applied reversal | " + wo.getWoNumber(),
                                    wo.getCostCentreValueId(), null, null, null));
        }

        glPosting.post(new JournalEntryDraft(
                wo.getCompanyId(), wo.getBranchId(), postingDate,
                "WO " + wo.getWoNumber() + " cancel: labour/overhead reversal",
                JournalSourceType.PRODUCTION_LABOUR, wo.getUid(),
                null, postedBy, lines));
    }

    /** Value carrier for a costed component leg. */
    public record ComponentCostLeg(String componentCode, BigDecimal value) {}
}
