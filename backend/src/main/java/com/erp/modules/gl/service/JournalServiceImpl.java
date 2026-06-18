package com.erp.modules.gl.service;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalLineDto;
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
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
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
    private final ScopeGuard scopeGuard;

    public JournalServiceImpl(GLPostingService postingService,
                               JournalEntryRepository entries,
                               JournalLineRepository lineRepo,
                               JournalBatchRepository batches,
                               ChartOfAccountRepository accounts,
                               CompanyRepository companies,
                               ScopeGuard scopeGuard) {
        this.postingService = postingService;
        this.entries        = entries;
        this.lineRepo       = lineRepo;
        this.batches        = batches;
        this.accounts       = accounts;
        this.companies      = companies;
        this.scopeGuard     = scopeGuard;
    }

    @Override
    public JournalEntryDto postManual(PostJournalRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> NotFoundException.of("Company", req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

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
                companyId, null, req.postingDate(), req.description(),
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
    public JournalEntryDto postManualReversal(String originalEntryUid, LocalDate reversalDate) {
        JournalEntry original = entries.findByUid(originalEntryUid)
                .orElseThrow(() -> NotFoundException.of("JournalEntry", originalEntryUid));
        scopeGuard.assertCanActIn(RequestContext.get(), original.getCompanyId());

        LocalDate date = reversalDate != null ? reversalDate : LocalDate.now();
        return postingService.postReversal(
                originalEntryUid, date, JournalSourceType.MANUAL, null, actorId());
    }

    // -------------------------------------------------------------------------

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
