package com.erp.modules.gl.service;

import com.erp.modules.costing.domain.entity.Dimension;
import com.erp.modules.costing.domain.entity.DimensionValue;
import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalLineDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.entity.JournalBatch;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalBatchRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The GL posting engine (ADR-0013 D-3). Validates then writes batch + entry + lines atomically.
 * This is the ONLY write path for the append-only ledger (BR-GL-02, NFR-GL-04).
 *
 * <p>Enforcement split (D-3 table):
 * <ul>
 *   <li>Service: ≥2 lines, Σ debit==Σ credit (BigDecimal), each line one-sided, active accounts,
 *       same company, base currency, OPEN period.</li>
 *   <li>DB CHECK: chk_journal_line_one_side (single-row backstop).</li>
 * </ul>
 */
@Service
@Transactional
public class GLPostingServiceImpl implements GLPostingService {

    private final FiscalPeriodResolver periodResolver;
    private final JournalBatchNumberGenerator batchNumberGen;
    private final ChartOfAccountRepository accounts;
    private final JournalBatchRepository batches;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lines;
    private final CompanyRepository companies;
    private final AuditService audit;
    // ADR-0025 D-4/D-7: GL re-asserts dimension validity via table-level read projections
    // (no gl → costing.service edge — the two table-level read repositories are the approved boundary).
    private final DimensionValueRepository dimensionValues;
    private final DimensionRepository      dimensionTypes;

    public GLPostingServiceImpl(FiscalPeriodResolver periodResolver,
                                 JournalBatchNumberGenerator batchNumberGen,
                                 ChartOfAccountRepository accounts,
                                 JournalBatchRepository batches,
                                 JournalEntryRepository entries,
                                 JournalLineRepository lines,
                                 CompanyRepository companies,
                                 AuditService audit,
                                 DimensionValueRepository dimensionValues,
                                 DimensionRepository dimensionTypes) {
        this.periodResolver   = periodResolver;
        this.batchNumberGen   = batchNumberGen;
        this.accounts         = accounts;
        this.batches          = batches;
        this.entries          = entries;
        this.lines            = lines;
        this.companies        = companies;
        this.audit            = audit;
        this.dimensionValues  = dimensionValues;
        this.dimensionTypes   = dimensionTypes;
    }

    @Override
    public JournalEntryDto post(JournalEntryDraft draft) {
        // 1. ≥2 lines (BR-GL-01)
        List<JournalEntryDraft.LineDraft> draftLines = draft.lines();
        if (draftLines == null || draftLines.size() < 2) {
            throw new IllegalArgumentException(
                    "A journal entry requires at least 2 lines (BR-GL-01). Got: "
                            + (draftLines == null ? 0 : draftLines.size()));
        }

        // 2. Validate each line: one-sided, positive amount, active account, same company, base currency.
        //    YEAR_END_CLOSE (the closing journal + its reopen reversal) is the ONE source allowed to
        //    post to an INACTIVE account — it must be able to ZERO a P&L account that was deactivated
        //    while still carrying a year balance (BR-GL-07 permits deactivating an account that has
        //    postings, so the close must still be able to clear it). BR-GL-04 stays enforced for every
        //    other source type. (Year-End Close adversarial review, ISSUES-REGISTER #14.)
        String baseCurrency = resolveBaseCurrency(draft.companyId());
        boolean allowInactive       = draft.sourceType() == JournalSourceType.YEAR_END_CLOSE;
        // Only user-entered MANUAL journals are subject to the allowManualPosting gate.
        // System/event-driven sources post to control accounts by design (ADR-0013 D-3).
        boolean isManualJournal     = draft.sourceType() == JournalSourceType.MANUAL;
        for (JournalEntryDraft.LineDraft ld : draftLines) {
            validateLine(ld, draft.companyId(), baseCurrency, allowInactive, isManualJournal);
        }

        // 2b. Dimension validation (ADR-0025 D-4 steps 2-3, BR-CC-04, FR-CC-08).
        //     GL re-asserts via table-level read projections — no gl → costing.service edge (D-7).
        //     Step 2: for each non-null dimension value id, assert active + correct slot + same company.
        //     Step 3: for each mandatory dimension slot, assert every line carries a value.
        //     Both checks are no-ops when no dimension is configured (NFR-CC-01 zero-regression guard).
        //     ADR-0025 amendment (ISSUES-REGISTER #20d): Step-3 MANDATORY enforcement applies ONLY to
        //     user-entered MANUAL journals. Event-driven/system posters (sales, AP/AR settlement,
        //     inventory, payroll, depreciation, FX, year-end close) do not carry operator dimension
        //     context, so enforcing mandatory slots on them turned automated postings (e.g. a
        //     STOCK.RECEIVED goods-receipt) into poison events. Step 2 (validity of any tag present)
        //     still applies to ALL postings.
        validateDimensions(draft.companyId(), draftLines, draft.sourceType());

        // 3. Σ debit == Σ credit (BR-GL-01, NFR-GL-02 — BigDecimal exact comparison)
        BigDecimal totalDebit  = draftLines.stream()
                .map(l -> l.debitAmount()  != null ? l.debitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = draftLines.stream()
                .map(l -> l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalDebit.compareTo(totalCredit) != 0) {
            throw new IllegalArgumentException(
                    "Journal entry is unbalanced (BR-GL-01): Σ debits=" + totalDebit
                            + ", Σ credits=" + totalCredit
                            + ", difference=" + totalDebit.subtract(totalCredit).toPlainString());
        }

        // 4. Resolve OPEN fiscal period for the posting date (BR-GL-03)
        FiscalPeriod period = periodResolver.resolveOpen(draft.companyId(), draft.postingDate());

        // 5. Allocate JB-#### (concurrency-safe, ADR-0007 D-6/ADR-0013 D-3)
        String batchNumber = batchNumberGen.next(draft.companyId());

        // 6. Persist batch
        JournalBatch batch = batches.save(new JournalBatch(
                draft.companyId(), draft.branchId(), batchNumber,
                draft.sourceType(), draft.description(), draft.postedBy(), draft.postedBy()));

        // 7. Persist entry
        JournalEntry entry = new JournalEntry(
                draft.companyId(), draft.branchId(), batch.getId(),
                draft.postingDate(), draft.description(),
                draft.sourceType(), draft.sourceRef(),
                draft.postedBy(), draft.postedBy());
        entry.setFiscalPeriodId(period.getId());
        if (draft.reversalOfId() != null) {
            entry.setReversalOfId(draft.reversalOfId());
        }
        entry = entries.save(entry);

        // 8. Persist lines — pass through dimension value ids from the draft (ADR-0025 D-4)
        //    and project dimension ids (ADR-0033 D-1). All ids are nullable (null = untagged).
        List<JournalLine> savedLines = new ArrayList<>();
        int lineNo = 1;
        for (JournalEntryDraft.LineDraft ld : draftLines) {
            BigDecimal debit  = ld.debitAmount()  != null ? ld.debitAmount()  : BigDecimal.ZERO;
            BigDecimal credit = ld.creditAmount() != null ? ld.creditAmount() : BigDecimal.ZERO;
            JournalLine line;
            if (debit.compareTo(BigDecimal.ZERO) > 0) {
                line = JournalLine.debit(draft.companyId(), draft.branchId(), entry.getId(),
                        lineNo, ld.accountId(), debit, ld.currency(), ld.lineMemo(),
                        draft.postedBy(),
                        ld.costCentreValueId(), ld.departmentValueId(),
                        ld.dimension3ValueId(), ld.dimension4ValueId(),
                        ld.projectId(), ld.projectTaskId(), ld.projectCostType());
            } else {
                line = JournalLine.credit(draft.companyId(), draft.branchId(), entry.getId(),
                        lineNo, ld.accountId(), credit, ld.currency(), ld.lineMemo(),
                        draft.postedBy(),
                        ld.costCentreValueId(), ld.departmentValueId(),
                        ld.dimension3ValueId(), ld.dimension4ValueId(),
                        ld.projectId(), ld.projectTaskId(), ld.projectCostType());
            }
            savedLines.add(lines.save(line));
            lineNo++;
        }

        // 9. Audit GL.JOURNAL.POST (NFR-GL-06)
        audit.record(AuditEvent.of(AuditActions.GL_JOURNAL_POST, "journal_entries",
                        entry.getId(), entry.getUid())
                .detail(Map.of(
                        "batchNumber", batchNumber,
                        "sourceType",  draft.sourceType().name(),
                        "postingDate", draft.postingDate().toString(),
                        "totalDebit",  totalDebit.toPlainString())));

        return toDto(entry, batchNumber, savedLines);
    }

    @Override
    public JournalEntryDto postReversal(String originalEntryUid, LocalDate reversalDate,
                                         JournalSourceType sourceType, String sourceRef,
                                         Long postedBy) {
        JournalEntry original = entries.findByUid(originalEntryUid)
                .orElseThrow(() -> new NotFoundException("JournalEntry not found: " + originalEntryUid));

        List<JournalLine> originalLines = lines.findByEntryIdOrderByLineNo(original.getId());

        // Build reversed draft: swap debit/credit on every line (BR-GL-11).
        // Carry dimension tags AND project tags from the original line (ADR-0025 D-4, ADR-0033 D-1).
        List<JournalEntryDraft.LineDraft> reversedLines = originalLines.stream()
                .map(l -> new JournalEntryDraft.LineDraft(
                        l.getAccountId(),
                        l.getCreditAmount(),   // original credit becomes debit
                        l.getDebitAmount(),    // original debit becomes credit
                        l.getCurrency(),
                        l.getLineMemo(),
                        l.getCostCentreValueId(),
                        l.getDepartmentValueId(),
                        l.getDimension3ValueId(),
                        l.getDimension4ValueId(),
                        l.getProjectId(),
                        l.getProjectTaskId(),
                        l.getProjectCostType()
                ))
                .toList();

        JournalEntryDraft reversalDraft = new JournalEntryDraft(
                original.getCompanyId(),
                original.getBranchId(),
                reversalDate,
                "Reversal of entry " + originalEntryUid,
                sourceType,
                sourceRef,
                original.getId(),
                postedBy,
                reversedLines
        );

        return post(reversalDraft);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * ADR-0025 D-4 steps 2-3: dimension validation inside the GL posting engine.
     *
     * <p>Step 2: for each non-null dimension value id on any LineDraft, assert the value is
     * active, belongs to the dimension occupying the expected slot, and lives in companyId.
     * Rejects cross-company values, inactive values, and wrong-slot values (BR-CC-04).
     *
     * <p>Step 3: for each dimension whose {@code is_mandatory=true} for this company, assert every
     * line carries a non-null value for that slot. Rejects the whole post if any line is missing a
     * mandatory dimension (FR-CC-08, BR-CC-03).
     *
     * <p>Both steps are no-ops when no dimension is configured (mandatorySlots empty, all ids null)
     * — the zero-regression guarantee (NFR-CC-01).
     *
     * <p>GL reads the costing tables directly via narrow repository projections (not via
     * DimensionResolver service) — the approved table-level read edge (D-7).
     */
    private void validateDimensions(Long companyId,
                                    List<JournalEntryDraft.LineDraft> draftLines,
                                    JournalSourceType sourceType) {
        // Step 2: validate each non-null dimension value id (ALL postings — a tag that IS present
        // must be active, correct-slot, same-company regardless of who posted it).
        for (JournalEntryDraft.LineDraft ld : draftLines) {
            assertDimensionValueValid(companyId, DimensionSlot.COST_CENTRE, ld.costCentreValueId());
            assertDimensionValueValid(companyId, DimensionSlot.DEPARTMENT,  ld.departmentValueId());
            assertDimensionValueValid(companyId, DimensionSlot.DIMENSION_3, ld.dimension3ValueId());
            assertDimensionValueValid(companyId, DimensionSlot.DIMENSION_4, ld.dimension4ValueId());
        }

        // Step 3 (MANUAL-only) — dimension REQUIREDNESS. Two complementary controls, both applying ONLY to
        // user-entered MANUAL journals. Automated/system posters do not carry operator dimension context;
        // enforcing requiredness on them poisons event-driven postings (ISSUES-REGISTER #20d, ADR-0025).
        // The operator who enters a manual journal CAN supply the dimension, so the controls are kept
        // exactly where they are actionable. Neither control adds/removes/alters a leg → Σ-gate untouched.
        if (sourceType != JournalSourceType.MANUAL) {
            return;
        }

        // Step 3a — per-account dimension requirement flags (ADR-0041 D4). MANUAL-only (we are past the
        // sourceType guard above). For each line whose target account carries a require_* flag, assert the
        // line supplies the matching dimension value; else reject naming the account + the missing slot.
        // Same exemption rationale as the company mandatory-slot rule below and the control-account gate:
        // system/event-driven posters do not carry operator dimension context (ADR-0025). No balance
        // impact — this is a pre-persist validation only. No-op when no account sets any flag
        // (zero-regression for existing manual journals, NFR-CC-01).
        enforceAccountDimensionRequirements(draftLines);

        // Step 3b — company-wide mandatory-slot enforcement (ADR-0025 amendment, ISSUES-REGISTER #20d) —
        // applies ONLY to user-entered MANUAL journals. Automated/system posters do not carry operator
        // dimension context; enforcing mandatory slots on them poisons event-driven postings. The operator
        // who enters a manual journal CAN supply the dimension, so the BR-CC-03/FR-CC-08 control is kept
        // exactly where it is actionable.
        // Skip entirely if no mandatory dimension exists (NFR-CC-01 zero-regression guard).
        List<DimensionSlot> mandatory = dimensionTypes.findMandatorySlots(companyId);
        if (mandatory.isEmpty()) {
            return;
        }
        Set<DimensionSlot> mandatorySet = mandatory.size() == 1
                ? Set.of(mandatory.get(0))
                : java.util.EnumSet.copyOf(mandatory);

        for (JournalEntryDraft.LineDraft ld : draftLines) {
            for (DimensionSlot slot : mandatorySet) {
                Long valueId = switch (slot) {
                    case COST_CENTRE -> ld.costCentreValueId();
                    case DEPARTMENT  -> ld.departmentValueId();
                    case DIMENSION_3 -> ld.dimension3ValueId();
                    case DIMENSION_4 -> ld.dimension4ValueId();
                };
                if (valueId == null) {
                    throw new IllegalArgumentException(
                            "Dimension slot " + slot + " is mandatory for company " + companyId
                                    + " (FR-CC-08 / BR-CC-03). Journal line for account "
                                    + ld.accountId() + " is missing a value for this slot.");
                }
            }
        }
    }

    /**
     * ADR-0041 D4: per-account dimension-requirement enforcement. For each posting line whose target
     * account sets {@code require_cost_centre}/{@code require_department}/{@code require_project}=true,
     * assert the line carries the corresponding dimension value (cost-centre value, department value,
     * project id respectively); else throw {@link com.erp.platform.common.api.ConflictException} naming
     * the account code and the missing dimension.
     *
     * <p>MANUAL-only by construction — this is reached only after the {@code sourceType==MANUAL} guard in
     * {@link #validateDimensions}. System/event-driven posters are exempt for the same reason as the
     * company mandatory-slot rule and the control-account gate (no operator dimension context, ADR-0025).
     *
     * <p>No balance impact: pre-persist validation only — it never alters, adds, or removes a leg, so the
     * Σ debit==Σ credit gate is untouched. No-op when no account on the draft sets any flag (NFR-CC-01).
     */
    private void enforceAccountDimensionRequirements(List<JournalEntryDraft.LineDraft> draftLines) {
        for (JournalEntryDraft.LineDraft ld : draftLines) {
            ChartOfAccount account = accounts.findById(ld.accountId())
                    .orElseThrow(() -> new NotFoundException("Account not found: " + ld.accountId()));
            if (account.isRequireCostCentre() && ld.costCentreValueId() == null) {
                throw new com.erp.platform.common.api.ConflictException(
                        "Account " + account.getAccountCode()
                                + " requires a cost-centre dimension on every manual journal line "
                                + "(ADR-0041 D4). This line is missing a cost-centre value.");
            }
            if (account.isRequireDepartment() && ld.departmentValueId() == null) {
                throw new com.erp.platform.common.api.ConflictException(
                        "Account " + account.getAccountCode()
                                + " requires a department dimension on every manual journal line "
                                + "(ADR-0041 D4). This line is missing a department value.");
            }
            if (account.isRequireProject() && ld.projectId() == null) {
                throw new com.erp.platform.common.api.ConflictException(
                        "Account " + account.getAccountCode()
                                + " requires a project dimension on every manual journal line "
                                + "(ADR-0041 D4). This line is missing a project value.");
            }
        }
    }

    /**
     * Assert a single dimension value id is valid for posting:
     * active, correct slot, same company (BR-CC-04). No-op when valueId is null (untagged line).
     *
     * <p>Table-level read — GL reads dimension_values projection directly (ADR-0025 D-7).
     */
    private void assertDimensionValueValid(Long companyId, DimensionSlot expectedSlot, Long valueId) {
        if (valueId == null) {
            return; // untagged — always valid (BR-CC-03)
        }
        DimensionValue v = dimensionValues.findByIdAndCompanyId(valueId, companyId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dimension value id=" + valueId + " not found in company " + companyId
                                + " (BR-CC-04 — cross-company or unknown value rejected)."));
        if (!v.isActive()) {
            throw new IllegalArgumentException(
                    "Dimension value id=" + valueId + " (code='" + v.getCode()
                            + "') is inactive; cannot tag a posting with it (BR-CC-04 / FR-CC-07).");
        }
        Dimension dim = dimensionTypes.findById(v.getDimensionId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Dimension not found for value id=" + valueId));
        if (dim.getSlot() != expectedSlot) {
            throw new IllegalArgumentException(
                    "Dimension value id=" + valueId + " belongs to slot " + dim.getSlot()
                            + " but was supplied in slot " + expectedSlot
                            + " (BR-CC-04 — wrong-slot value rejected).");
        }
    }

    private String resolveBaseCurrency(Long companyId) {
        var company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        return company.getBaseCurrency();
    }

    private void validateLine(JournalEntryDraft.LineDraft ld, Long companyId, String baseCurrency,
                              boolean allowInactiveAccount, boolean enforceManualPostingGate) {
        BigDecimal debit  = ld.debitAmount()  != null ? ld.debitAmount()  : BigDecimal.ZERO;
        BigDecimal credit = ld.creditAmount() != null ? ld.creditAmount() : BigDecimal.ZERO;

        // One-sided check (BR-GL-08) — service enforces before the DB CHECK fires
        boolean hasDebit  = debit.compareTo(BigDecimal.ZERO) > 0;
        boolean hasCredit = credit.compareTo(BigDecimal.ZERO) > 0;
        if (!(hasDebit ^ hasCredit)) {
            throw new IllegalArgumentException(
                    "Journal line must have a positive debit OR a positive credit, not both or neither "
                            + "(BR-GL-08). debit=" + debit + ", credit=" + credit);
        }

        // Non-negative check
        if (debit.compareTo(BigDecimal.ZERO) < 0 || credit.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(
                    "Journal line amounts must be non-negative (BR-GL-08).");
        }

        // Active account, same company (BR-GL-04/BR-GL-05)
        ChartOfAccount account = accounts.findById(ld.accountId())
                .orElseThrow(() -> new NotFoundException("Account not found: " + ld.accountId()));
        if (!account.getCompanyId().equals(companyId)) {
            throw new IllegalArgumentException(
                    "Account " + account.getUid() + " belongs to company " + account.getCompanyId()
                            + " but the entry is for company " + companyId + " (BR-GL-05).");
        }
        if (!account.isActive() && !allowInactiveAccount) {
            throw new IllegalArgumentException(
                    "Account " + account.getAccountCode() + " is inactive; cannot post to it (BR-GL-04).");
        }

        // Manual-posting gate: only user-entered MANUAL journals are subject to this check.
        // System/event-driven posters (YEAR_END_CLOSE, inventory, AP/AR settlement, payroll, FX)
        // are exempted — they post to control accounts by design and do not carry operator context.
        if (enforceManualPostingGate && !account.isAllowManualPosting()) {
            throw new com.erp.platform.common.api.ConflictException(
                    "Account " + account.getAccountCode()
                            + " does not allow manual posting. Update the account's allowManualPosting flag to permit direct entries.");
        }
        // Belt-and-suspenders (D-1, ADR-0040): even if allowManualPosting was inadvertently left true
        // on a sub-ledger control account, reject the MANUAL journal at the control-type gate. Only
        // control types that block manual posting apply here — CASH/BANK are classified controls but
        // stay manually postable (reconciled via the cash/bank module), see ControlType.blocksManualPosting().
        if (enforceManualPostingGate
                && account.getControlType() != null
                && account.getControlType().blocksManualPosting()) {
            throw new com.erp.platform.common.api.ConflictException(
                    "Account " + account.getAccountCode()
                            + " is a control account (" + account.getControlType()
                            + ") and cannot be the target of a manual journal entry (D-1).");
        }

        // Base currency (BR-GL-06, D-9)
        if (!baseCurrency.equals(ld.currency())) {
            throw new IllegalArgumentException(
                    "Journal line currency " + ld.currency()
                            + " does not match company base currency " + baseCurrency + " (BR-GL-06).");
        }
    }

    private JournalEntryDto toDto(JournalEntry entry, String batchNumber, List<JournalLine> lineList) {
        List<JournalLineDto> lineDtos = lineList.stream()
                .map(l -> {
                    ChartOfAccount acct = accounts.findById(l.getAccountId()).orElse(null);
                    return new JournalLineDto(
                            l.getId(), l.getUid(), l.getLineNo(), l.getAccountId(),
                            acct != null ? acct.getAccountCode() : null,
                            acct != null ? acct.getName() : null,
                            l.getDebitAmount(), l.getCreditAmount(),
                            l.getCurrency(), l.getLineMemo(),
                            l.getTaxCode(), l.getTaxAmount());
                })
                .toList();

        return new JournalEntryDto(
                entry.getId(), entry.getUid(), entry.getCompanyId(),
                batchNumber, entry.getPostingDate(), entry.getFiscalPeriodId(),
                entry.getDescription(), entry.getSourceType(),
                entry.getSourceRef(), entry.getReversalOfId(),
                entry.getReversedByEntryId(), entry.isReversed(),
                CurrencyCode.value(entry.getHeaderCurrency()),
                entry.getTotalDebit(), entry.getTotalCredit(),
                entry.getPostedAt(), lineDtos);
    }
}
