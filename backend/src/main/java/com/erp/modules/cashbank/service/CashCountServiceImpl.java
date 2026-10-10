package com.erp.modules.cashbank.service;

import com.erp.modules.cashbank.domain.dto.CashCountDenominationDto;
import com.erp.modules.cashbank.domain.dto.CashCountDto;
import com.erp.modules.cashbank.domain.dto.OpenCashCountRequest;
import com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashCount;
import com.erp.modules.cashbank.domain.entity.CashCountDenomination;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashCountStatus;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashCountDenominationRepository;
import com.erp.modules.cashbank.repository.CashCountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import com.erp.modules.gl.repository.JournalLineRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import com.erp.modules.sales.service.SaleTenderAccountQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * End-of-day cash count against a CASH till (ADR-0050 D-7 PR-A).
 *
 * <p>Variance GL posting mirrors {@code PosSessionServiceImpl.postVarianceGl}'s shape but posts
 * synchronously in-TX (fail-fast, like {@link CashDirectEntryServiceImpl}) against the till's OWN
 * linked GL account (not the generic {@code gl_configs.CASH}), and additionally writes a linked
 * {@code DIRECT_ENTRY} cash_transaction so the cash-book and GL move together to the counted amount.
 *
 * <p><b>ARC-01 / ARC-08 — the sales cash account.</b> "Expected" is the till's cash-book balance
 * (ADR-0050 D-7.2). Sales, voids, POS payouts and POS over/short used to reach only the GL, so a
 * count on an account that takes sales booked the day's takings a second time as cash-over income
 * and was refused (wave 3). Since V106 the cash book mirrors all four (CashBookJournalMirror), so
 * the book balance is the drawer again and such counts are allowed, with two limits:
 * <ul>
 *   <li>a business day BEFORE the cash book carried sales ({@link CashBookGoLive}) is refused —
 *       that day's sales are in the GL only, so its book balance cannot be the drawer;</li>
 *   <li>everything the GL posted to the till's account before go-live but the cash book never saw
 *       (historic takings, payouts, POS over/short) is a frozen gap. It is added to the book
 *       balance — expected = book balance + (GL - cash book, both as at go-live) — so the first
 *       count after go-live does not book years of takings as cash over. Zero on a new install.</li>
 * </ul>
 * Accounts that never took a sale keep the plain ADR-0050 formula.
 */
@Service
@Transactional
public class CashCountServiceImpl implements CashCountService {

    private final CashCountRepository              counts;
    private final CashCountDenominationRepository  denominations;
    private final CashBankAccountRepository         accounts;
    private final CashTransactionRepository         txns;
    private final CompanyRepository                 companies;
    private final GlConfigRepository                glConfigs;
    private final CashBankNumberGenerator           numbers;
    private final GLConfigResolver                  glConfig;
    private final GLPostingService                  glPosting;
    private final ScopeGuard                         scopeGuard;
    private final AuditService                       audit;
    private final SaleTenderAccountQuery             saleTenderAccounts;
    private final CashBookGoLive                     goLive;
    private final JournalLineRepository              journalLines;

    public CashCountServiceImpl(CashCountRepository counts,
                                 CashCountDenominationRepository denominations,
                                 CashBankAccountRepository accounts,
                                 CashTransactionRepository txns,
                                 CompanyRepository companies,
                                 GlConfigRepository glConfigs,
                                 CashBankNumberGenerator numbers,
                                 GLConfigResolver glConfig,
                                 GLPostingService glPosting,
                                 ScopeGuard scopeGuard,
                                 AuditService audit,
                                 SaleTenderAccountQuery saleTenderAccounts,
                                 CashBookGoLive goLive,
                                 JournalLineRepository journalLines) {
        this.counts        = counts;
        this.denominations = denominations;
        this.accounts      = accounts;
        this.txns          = txns;
        this.companies     = companies;
        this.glConfigs     = glConfigs;
        this.numbers       = numbers;
        this.glConfig      = glConfig;
        this.glPosting     = glPosting;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
        this.saleTenderAccounts = saleTenderAccounts;
        this.goLive        = goLive;
        this.journalLines  = journalLines;
    }

    @Override
    public CashCountDto open(OpenCashCountRequest req) {
        Company company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        Long companyId = company.getId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        CashBankAccount till = accounts.findByCompanyIdAndUid(companyId, req.cashBankAccountUid())
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));
        if (till.getAccountType() != CashBankAccountType.CASH) {
            throw new IllegalArgumentException(
                    "A cash count can only be performed against a CASH till, not a bank account.");
        }
        if (!till.isActive()) {
            throw new IllegalStateException("The selected till is inactive and cannot be counted.");
        }
        // One live count per till/day: a second non-reconciled count would derive the same expected
        // and reconcile independently, posting the variance to GL + the cash book twice (D-7 review).
        if (counts.existsByCashAccountIdAndBusinessDateAndStatusIn(
                till.getId(), req.businessDate(), List.of(CashCountStatus.OPEN, CashCountStatus.COUNTED))) {
            throw new ConflictException(
                    "An open cash count already exists for this till on this date. "
                            + "Complete or reconcile it before starting another.");
        }

        String currency = company.getBaseCurrency();
        BigDecimal expected = expectedFor(companyId, till, req.businessDate());

        Long actor = actorId();
        Long branch = till.getBranchId() != null ? till.getBranchId() : branchId();
        String countNumber = numbers.nextCashCount(companyId);

        CashCount count = new CashCount(companyId, branch, till.getId(), countNumber,
                actor, req.businessDate(), expected, currency, actor);
        count = counts.save(count);

        audit.record(AuditEvent.of(AuditActions.CASH_COUNT_OPEN, "cash_counts",
                        count.getId(), count.getUid())
                .detail(Map.of("countNumber", countNumber, "expectedAmount", expected.toPlainString())));

        return toDto(count, till);
    }

    @Override
    public CashCountDto recordDenominations(String uid, RecordDenominationsRequest req) {
        CashCount count = Lookups.orNotFound(counts.findByUid(uid), "CashCount", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), count.getCompanyId());

        if (count.getStatus() == CashCountStatus.RECONCILED) {
            throw new ConflictException(
                    "This cash count has already been reconciled and can no longer be edited.");
        }

        Long actor = actorId();

        // Replace child rows: bulk-delete (immediate) then insert the fresh breakdown, so a
        // re-submitted denomination value never trips uq_cash_count_denom against the old rows.
        denominations.deleteByCashCountId(count.getId());

        BigDecimal counted = BigDecimal.ZERO;
        List<CashCountDenomination> lines = new ArrayList<>();
        for (RecordDenominationsRequest.Line line : req.lines()) {
            BigDecimal lineAmount = line.denomination().multiply(BigDecimal.valueOf(line.quantity()));
            lines.add(new CashCountDenomination(count.getId(), line.denomination(),
                    line.quantity(), lineAmount, actor));
            counted = counted.add(lineAmount);
        }
        denominations.saveAll(lines);

        BigDecimal variance = counted.subtract(count.getExpectedAmount());
        count.markCounted(counted, variance, actor);
        count = counts.save(count);

        audit.record(AuditEvent.of(AuditActions.CASH_COUNT_COUNT, "cash_counts",
                        count.getId(), count.getUid())
                .detail(Map.of("countedAmount", counted.toPlainString(),
                        "varianceAmount", variance.toPlainString())));

        return toDto(count);
    }

    @Override
    public CashCountDto reconcile(String uid) {
        CashCount count = Lookups.orNotFound(counts.findByUid(uid), "CashCount", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), count.getCompanyId());

        // Idempotent: reconciling an already-RECONCILED count is a no-op (no double-post).
        if (count.getStatus() == CashCountStatus.RECONCILED) {
            return toDto(count);
        }
        if (count.getStatus() != CashCountStatus.COUNTED) {
            throw new ConflictException(
                    "Record the denomination count before reconciling this cash count.");
        }

        // ARC-01: a count on a sales-taking account opened before the cash book carried sales
        // derived an "expected" without them - its "over" would be the day's takings again.
        CashBankAccount countedTill = accounts
                .findByCompanyIdAndId(count.getCompanyId(), count.getCashAccountId())
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));
        if (takesSales(count.getCompanyId(), countedTill)) {
            Instant since = goLive.since().orElse(null);
            if (since == null || count.getCreatedAt() == null
                    || count.getCreatedAt().isBefore(since)) {
                throw new ConflictException(
                        "This count was started before the cash book carried your sales, so its "
                                + "expected amount leaves the takings out. Start a new count for "
                                + "this till instead.");
            }
        }

        Long actor = actorId();
        BigDecimal variance = count.getVarianceAmount();
        String journalUid = null;

        if (variance != null && variance.compareTo(BigDecimal.ZERO) != 0) {
            CashBankAccount till = countedTill;

            String currency = count.getCurrency().value();
            BigDecimal abs = variance.abs();
            LineDraft debitLine;
            LineDraft creditLine;
            CashTxnDirection direction;
            Long counterGlAccountId;

            if (variance.compareTo(BigDecimal.ZERO) > 0) {
                // Over: DR till cash-GL / CR POS_CASH_OVER (income) — mirrors
                // PosSessionServiceImpl.postVarianceGl, but against the till's OWN GL account.
                var overAcct = glConfig.resolve(count.getCompanyId(), GlConfigKey.POS_CASH_OVER);
                debitLine  = new LineDraft(till.getGlAccountId(), abs, BigDecimal.ZERO, currency,
                        "Cash count over — " + count.getCountNumber());
                creditLine = new LineDraft(overAcct.getId(), BigDecimal.ZERO, abs, currency,
                        "Cash count over income — " + count.getCountNumber());
                counterGlAccountId = overAcct.getId();
                direction = CashTxnDirection.IN;
            } else {
                // Short: DR POS_CASH_SHORT (expense) / CR till cash-GL
                var shortAcct = glConfig.resolve(count.getCompanyId(), GlConfigKey.POS_CASH_SHORT);
                debitLine  = new LineDraft(shortAcct.getId(), abs, BigDecimal.ZERO, currency,
                        "Cash count short expense — " + count.getCountNumber());
                creditLine = new LineDraft(till.getGlAccountId(), BigDecimal.ZERO, abs, currency,
                        "Cash count short — " + count.getCountNumber());
                counterGlAccountId = shortAcct.getId();
                direction = CashTxnDirection.OUT;
            }

            JournalEntryDraft draft = new JournalEntryDraft(
                    count.getCompanyId(), count.getBranchId(), count.getBusinessDate(),
                    "Cash count variance " + count.getCountNumber(),
                    JournalSourceType.POS_VARIANCE, count.getUid(), null, actor,
                    List.of(debitLine, creditLine));
            JournalEntryDto posted = glPosting.post(draft);
            journalUid = posted.uid();

            // Linked cash-book row so the cash-book and GL move together (ADR-0050 D-7.1).
            String txnNumber = numbers.nextTransaction(count.getCompanyId());
            CashTransaction txn = new CashTransaction(
                    count.getCompanyId(), count.getBranchId(), till.getId(), txnNumber,
                    count.getBusinessDate(), direction, abs, currency,
                    CashTxnType.DIRECT_ENTRY, count.getUid(), counterGlAccountId,
                    "Cash count variance " + count.getCountNumber(), actor);
            txn.setJournalEntryRef(journalUid);
            txns.save(txn);
        }

        count.markReconciled(journalUid, actor);
        count = counts.save(count);

        audit.record(AuditEvent.of(AuditActions.CASH_COUNT_RECONCILE, "cash_counts",
                        count.getId(), count.getUid())
                .detail(Map.of("variance", variance == null ? "0" : variance.toPlainString())));

        return toDto(count);
    }

    @Override
    @Transactional(readOnly = true)
    public CashCountDto getByUid(String uid) {
        CashCount count = Lookups.orNotFound(counts.findByUid(uid), "CashCount", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), count.getCompanyId());
        return toDto(count);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CashCountDto> listByAccount(Long companyId, Long accountId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        // Ownership check before listing (confused-deputy guard, mirrors CashDirectEntryServiceImpl).
        accounts.findByCompanyIdAndId(companyId, accountId)
                .orElseThrow(() -> new NotFoundException("Cash/bank account not found."));
        return counts.findByCashAccountIdOrderByBusinessDateDescIdDesc(accountId).stream()
                .map(this::toDto)
                .toList();
    }

    // -------------------------------------------------------------------------

    /**
     * The derived "expected" for a count (ADR-0050 D-7.2 + ARC-01/ARC-08): the cash-book balance
     * as at the business date; on an account that takes sales, plus the frozen pre-go-live gap,
     * and refused for a day before the cash book carried sales. See the class comment.
     */
    private BigDecimal expectedFor(Long companyId, CashBankAccount till, LocalDate businessDate) {
        BigDecimal book = nz(txns.bookBalanceAsOf(till.getId(), businessDate));
        if (!takesSales(companyId, till)) {
            return book;
        }
        Instant since = goLive.since().orElse(null);
        if (since == null) {
            throw new ConflictException(
                    "This cash account receives your sales takings and cannot be counted here "
                            + "yet. Count this drawer when you close the POS till session.");
        }
        LocalDate firstDay = since.atZone(ZoneOffset.UTC).toLocalDate();
        if (businessDate.isBefore(firstDay)) {
            throw new ConflictException(
                    "This cash account receives your sales takings, and the cash book only "
                            + "carries them from "
                            + firstDay.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                            + ". A count for an earlier day cannot be checked here; that drawer "
                            + "was counted at the POS till session close.");
        }
        BigDecimal glBefore = nz(journalLines.accountBalancePostedBefore(
                companyId, till.getGlAccountId(), since));
        BigDecimal bookBefore = nz(txns.bookBalanceWrittenBefore(till.getId(), since));
        return book.add(glBefore.subtract(bookBefore));
    }

    /**
     * True for the cash account linked to the GL {@code CASH} account that cash sales, POS payouts
     * and POS over/short post to, and for any account a sale tender has named (ACC-05).
     */
    private boolean takesSales(Long companyId, CashBankAccount till) {
        Long salesCashGl = glConfigs.findByCompanyIdAndConfigKey(companyId, GlConfigKey.CASH)
                .map(c -> c.getAccountId())
                .orElse(null);
        if (salesCashGl != null && salesCashGl.equals(till.getGlAccountId())) {
            return true;
        }
        return saleTenderAccounts != null
                && saleTenderAccounts.takesSaleTenders(companyId, till.getId());
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    private CashCountDto toDto(CashCount count) {
        CashBankAccount till = accounts.findByCompanyIdAndId(count.getCompanyId(), count.getCashAccountId())
                .orElse(null);
        return toDto(count, till);
    }

    private CashCountDto toDto(CashCount count, CashBankAccount till) {
        List<CashCountDenominationDto> lines = denominations
                .findByCashCountIdOrderByDenominationDesc(count.getId()).stream()
                .map(d -> new CashCountDenominationDto(d.getDenomination(), d.getQuantity(), d.getLineAmount()))
                .toList();
        return new CashCountDto(
                count.getId(), count.getUid(), count.getCompanyId(),
                till != null ? till.getUid() : null,
                till != null ? till.getName() : null,
                count.getCountNumber(), count.getBusinessDate(),
                count.getExpectedAmount(), count.getCountedAmount(), count.getVarianceAmount(),
                count.getCurrency().value(), count.getStatus(), count.getJournalEntryRef(),
                lines, count.getCountedAt(), count.getReconciledAt());
    }
}
