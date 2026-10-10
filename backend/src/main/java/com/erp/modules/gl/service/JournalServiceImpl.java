package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalLineDto;
import com.erp.modules.gl.domain.dto.JournalSearchCriteria;
import com.erp.modules.gl.domain.dto.PostJournalRequest;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.entity.JournalLine;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalBatchRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class JournalServiceImpl implements JournalService {

    private final GLPostingService postingService;
    private final JournalEntryRepository entries;
    private final JournalLineRepository lineRepo;
    private final JournalBatchRepository batches;
    private final ChartOfAccountRepository accounts;
    private final CompanyRepository companies;
    private final BranchRepository branches;
    private final ScopeGuard scopeGuard;
    private final BranchReadGuard branchGuard;
    private final CompanyCalendar calendar;

    public JournalServiceImpl(GLPostingService postingService,
                               JournalEntryRepository entries,
                               JournalLineRepository lineRepo,
                               JournalBatchRepository batches,
                               ChartOfAccountRepository accounts,
                               CompanyRepository companies,
                               BranchRepository branches,
                               ScopeGuard scopeGuard,
                               BranchReadGuard branchGuard,
                               CompanyCalendar calendar) {
        this.postingService = postingService;
        this.entries        = entries;
        this.lineRepo       = lineRepo;
        this.batches        = batches;
        this.accounts       = accounts;
        this.companies      = companies;
        this.branches       = branches;
        this.scopeGuard     = scopeGuard;
        this.branchGuard    = branchGuard;
        this.calendar       = calendar;
    }

    @Override
    public JournalEntryDto postManual(PostJournalRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> NotFoundException.of("Company", req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Optional branch: resolved INSIDE the company (a foreign or unknown uid is a 404, never a
        // silent company-level post), then the caller's assignment is checked. No uid = company level.
        Long branchId = resolveBranch(companyId, req.branchUid());

        // Resolve each line's account uid → id, scoped to company
        List<JournalEntryDraft.LineDraft> draftLines = req.lines().stream()
                .map(l -> {
                    ChartOfAccount acct = accounts.findByCompanyIdAndUid(companyId, l.accountUid())
                            .orElseThrow(() -> NotFoundException.of("ChartOfAccount", l.accountUid()));
                    BigDecimal debit  = l.debitAmount()  != null ? l.debitAmount()  : BigDecimal.ZERO;
                    BigDecimal credit = l.creditAmount() != null ? l.creditAmount() : BigDecimal.ZERO;
                    // currency defaults to company base — the posting engine validates it
                    String currency = resolveCurrency(companyId);
                    return new JournalEntryDraft.LineDraft(
                            acct.getId(), debit, credit, currency, l.lineMemo());
                })
                .toList();

        // Issue #2 fix (a): the user-driven journal endpoint ALWAYS posts as MANUAL so that
        // GLPostingServiceImpl's allowManualPosting / control-account gate fires on every line,
        // regardless of what sourceType the caller supplied.  System/event-driven posters
        // (AR, AP, stock, payroll, cash) call GLPostingService.post() directly — they never go
        // through this method — so their system sourceTypes are unaffected.
        JournalSourceType sourceType = JournalSourceType.MANUAL;

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId, req.postingDate(), req.description(),
                sourceType, req.sourceRef(), null, actorId(), draftLines);

        return postingService.post(draft);
    }

    @Override
    @Transactional(readOnly = true)
    public JournalEntryDto getByUid(String uid) {
        JournalEntry entry = entries.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("JournalEntry", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), entry.getCompanyId());
        return toDto(entry);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JournalEntryDto> list(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return entries.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<JournalEntryDto> search(Long companyId, JournalSearchCriteria criteria,
                                        Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<JournalEntry> page;
        if (criteria == null || criteria.isEmpty()) {
            page = entries.findByCompanyId(companyId, pageable);
        } else {
            Long accountId = null;
            if (criteria.accountUid() != null && !criteria.accountUid().isBlank()) {
                accountId = accounts.findByCompanyIdAndUid(companyId, criteria.accountUid().trim())
                        .map(ChartOfAccount::getId)
                        .orElseThrow(() -> NotFoundException.of("ChartOfAccount",
                                criteria.accountUid()));
            }
            page = entries.findAll(JournalSearchSpecs.matching(companyId, criteria, accountId),
                    pageable);
        }
        List<JournalEntryDto> rows = page.getContent().stream().map(this::toDto).toList();
        List<JournalEntryDto> withRefs = withDocumentRefs(companyId, rows);
        return new PageImpl<>(withRefs, pageable, page.getTotalElements());
    }

    /**
     * Fills {@link JournalEntryDto#documentRef()} (ACC-19): from the entry's own description, else
     * from another journal of the same source document — a SALES journal says "Sale &lt;uid&gt;",
     * but the sale's COGS journal says "COGS — sale INV-0453".
     */
    private List<JournalEntryDto> withDocumentRefs(Long companyId, List<JournalEntryDto> rows) {
        java.util.Map<String, String> bySource = new java.util.HashMap<>();
        java.util.Set<String> unresolved = new java.util.HashSet<>();
        for (JournalEntryDto r : rows) {
            String own = JournalDocumentRefs.fromDescription(r.description(), r.sourceType());
            if (r.sourceRef() == null) {
                continue;
            }
            if (own != null) {
                bySource.putIfAbsent(r.sourceRef(), own);
            } else {
                unresolved.add(r.sourceRef());
            }
        }
        unresolved.removeAll(bySource.keySet());
        if (!unresolved.isEmpty()) {
            for (JournalEntry sibling : entries.findByCompanyIdAndSourceRefIn(companyId, unresolved)) {
                String ref = JournalDocumentRefs.fromDescription(
                        sibling.getDescription(), sibling.getSourceType());
                if (ref != null) {
                    bySource.putIfAbsent(sibling.getSourceRef(), ref);
                }
            }
        }
        return rows.stream()
                .map(r -> {
                    String own = JournalDocumentRefs.fromDescription(r.description(), r.sourceType());
                    String ref = own != null ? own
                            : r.sourceRef() != null ? bySource.get(r.sourceRef()) : null;
                    return ref != null ? r.withDocumentRef(ref) : r;
                })
                .toList();
    }

    @Override
    public JournalEntryDto postManualReversal(String originalEntryUid, LocalDate reversalDate,
                                              String reason) {
        JournalEntry original = entries.findByUid(originalEntryUid)
                .orElseThrow(() -> NotFoundException.of("JournalEntry", originalEntryUid));
        scopeGuard.assertCanActIn(RequestContext.get(), original.getCompanyId());

        // ACC-26: only a manual journal is corrected by reversing it here. A system journal (sale,
        // COGS, receipt, payment…) mirrors a document; reversing it alone would leave that document
        // standing with no ledger entry — the sub-ledger and the GL would silently disagree.
        if (original.getSourceType() != JournalSourceType.MANUAL) {
            throw new ConflictException("Only manual journals can be reversed here. This journal was"
                    + " posted automatically from a document; correct it from that document instead"
                    + " (for example void the invoice or reverse the receipt).");
        }

        LocalDate date = reversalDate != null ? reversalDate
                : calendar.today(original.getCompanyId());
        return postingService.postReversal(
                originalEntryUid, date, JournalSourceType.MANUAL, null, actorId(), reason);
    }

    // -------------------------------------------------------------------------

    private Long resolveBranch(Long companyId, String branchUid) {
        if (branchUid == null || branchUid.isBlank()) {
            return null;
        }
        Branch branch = branches.findByUidAndCompanyId(branchUid.trim(), companyId)
                .orElseThrow(() -> NotFoundException.of("Branch", branchUid));
        if (!branch.isUsableForSession()) {
            throw new ConflictException(
                    "That branch is no longer active, so journals cannot be posted to it.");
        }
        branchGuard.assertMayPostTo(RequestContext.get(), branch.getId());
        return branch.getId();
    }

    private String resolveCurrency(Long companyId) {
        return companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private JournalEntryDto toDto(JournalEntry entry) {
        List<JournalLine> jLines = lineRepo.findByEntryIdOrderByLineNo(entry.getId());
        String batchNumber = batches.findById(entry.getBatchId())
                .map(b -> b.getBatchNumber()).orElse(null);
        List<JournalLineDto> lineDtos = jLines.stream()
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
