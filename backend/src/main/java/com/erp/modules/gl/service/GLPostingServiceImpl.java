package com.erp.modules.gl.service;

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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public GLPostingServiceImpl(FiscalPeriodResolver periodResolver,
                                 JournalBatchNumberGenerator batchNumberGen,
                                 ChartOfAccountRepository accounts,
                                 JournalBatchRepository batches,
                                 JournalEntryRepository entries,
                                 JournalLineRepository lines,
                                 CompanyRepository companies,
                                 AuditService audit) {
        this.periodResolver   = periodResolver;
        this.batchNumberGen   = batchNumberGen;
        this.accounts         = accounts;
        this.batches          = batches;
        this.entries          = entries;
        this.lines            = lines;
        this.companies        = companies;
        this.audit            = audit;
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
        boolean allowInactive = draft.sourceType() == JournalSourceType.YEAR_END_CLOSE;
        for (JournalEntryDraft.LineDraft ld : draftLines) {
            validateLine(ld, draft.companyId(), baseCurrency, allowInactive);
        }

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

        // 8. Persist lines — pass through dimension value ids from the draft (ADR-0025 D-4).
        //    All four ids are nullable: when null the line is untagged, identical to pre-V27 (NFR-CC-01).
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
                        ld.dimension3ValueId(), ld.dimension4ValueId());
            } else {
                line = JournalLine.credit(draft.companyId(), draft.branchId(), entry.getId(),
                        lineNo, ld.accountId(), credit, ld.currency(), ld.lineMemo(),
                        draft.postedBy(),
                        ld.costCentreValueId(), ld.departmentValueId(),
                        ld.dimension3ValueId(), ld.dimension4ValueId());
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
        // Carry the dimension tags from the original line (the reversal tags the same
        // cost centres/departments as the entry being reversed — ADR-0025 D-4).
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
                        l.getDimension4ValueId()
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

    private String resolveBaseCurrency(Long companyId) {
        var company = companies.findById(companyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + companyId));
        return company.getBaseCurrency();
    }

    private void validateLine(JournalEntryDraft.LineDraft ld, Long companyId, String baseCurrency,
                              boolean allowInactiveAccount) {
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
                            l.getCurrency(), l.getLineMemo());
                })
                .toList();

        return new JournalEntryDto(
                entry.getId(), entry.getUid(), entry.getCompanyId(),
                batchNumber, entry.getPostingDate(), entry.getFiscalPeriodId(),
                entry.getDescription(), entry.getSourceType(),
                entry.getSourceRef(), entry.getReversalOfId(),
                entry.getPostedAt(), lineDtos);
    }
}
