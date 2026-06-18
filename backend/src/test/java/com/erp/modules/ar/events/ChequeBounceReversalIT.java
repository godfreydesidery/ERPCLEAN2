package com.erp.modules.ar.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.entity.ArReceipt;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.ar.service.ArOpeningBalanceService;
import com.erp.modules.ar.service.ArReceiptService;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.Cheque;
import com.erp.modules.cashbank.domain.enums.ChequeStatus;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.ChequeRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.cashbank.service.ChequeService;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link ChequeBounceReversalHandler} (ADR-0041 D3, P2 — closes the deferred
 * D-9 follow-up).
 *
 * <p>Scenario: an INBOUND cheque tied to a confirmed AR receipt is DEPOSITED, then BOUNCED via
 * {@link com.erp.modules.cashbank.service.ChequeServiceImpl#bounce}. The bounce publishes
 * {@code CHEQUE.BOUNCED} on the outbox; dispatching it drives the handler to:
 * <ol>
 *   <li>post an APPEND-ONLY reversing JournalEntry — the exact inverse of the receipt's cash leg
 *       (DR Cash / CR AR → DR AR / CR Cash), so ΣDR == ΣCR by construction;</li>
 *   <li>restore the relieved invoice outstanding (face + base) and status;</li>
 *   <li>stamp {@code ar_receipts.reversed_at}.</li>
 * </ol>
 *
 * <p>Idempotency: a second dispatch of the same BOUNCED event must NOT post a second reversal
 * (no double-reversal; receipt already stamped).
 */
class ChequeBounceReversalIT extends PostgresIntegrationTest {

    @Autowired private ArReceiptService          receiptService;
    @Autowired private ArOpeningBalanceService   openingBalanceService;
    @Autowired private ArGlSeeder                arGlSeeder;
    @Autowired private CustomerService           customerService;
    @Autowired private ChequeService             chequeService;
    @Autowired private ChequeRepository          chequeRepo;
    @Autowired private CashBankAccountRepository cashAccountRepo;
    @Autowired private ArInvoiceRepository       arInvoiceRepo;
    @Autowired private ArReceiptRepository       arReceiptRepo;
    @Autowired private JournalEntryRepository    journalEntryRepo;
    @Autowired private JournalLineRepository     journalLineRepo;
    @Autowired private ChartOfAccountRepository  accountRepo;
    @Autowired private ChartOfAccountService     chartOfAccountService;
    @Autowired private FiscalCalendarService     fiscalCalendarService;
    @Autowired private GlConfigService           glConfigService;
    @Autowired private CashBankSeeder            cashBankSeeder;
    @Autowired private DomainEventRepository     domainEventRepository;
    @Autowired private DomainEventDispatcher     dispatcher;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private IamTestData               testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  customerUid;

    private static final String TZS = "TZS";
    /** Full open-item / receipt face. */
    private static final BigDecimal AMOUNT = new BigDecimal("1180");

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("ChqBounce IT Org"));
        company    = companies.save(new Company(org, "CHQB", "ChqBounce IT Co"));
        branch     = branches.save(new Branch(company, "CHQB1", "ChqBounce IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("chqb_root", passwordEncoder.encode("Root1234!"), "ChqB Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setContext();

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Bounce Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void setContext() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "chqb_root", true, company.getId(), branch.getId(), null));
    }

    // =========================================================================
    // Bar 1: bounce → append-only reversal posted, invoice restored, receipt stamped
    // =========================================================================

    @Test
    void bounce_inboundChequeForReceipt_reversesCashLeg_restoresInvoice_stampsReceipt() {
        // Open item + confirmed full receipt (cash leg posts to GL → invoice PAID)
        ArInvoiceDto inv = openItem(AMOUNT);
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, AMOUNT, TZS, LocalDate.now(), "CHEQUE", null, List.of()));
        assertThat(receipt.glEntryUid()).isNotBlank();
        assertThat(arInvoiceRepo.findByUid(inv.uid()).orElseThrow().getStatus())
                .isEqualTo(ArInvoiceStatus.PAID);

        // Register an INBOUND cheque tied to the receipt, deposit, then bounce.
        Cheque cheque = depositedInboundCheque(receipt.uid());
        long jeBefore = countReceiptJournalEntries();

        chequeService.bounce(cheque.getUid(), "Insufficient funds");
        dispatchBounceEvent(cheque.getUid());
        setContext();

        // (1) An append-only reversing AR_RECEIPT journal entry was added (count grew by one).
        long jeAfter = countReceiptJournalEntries();
        assertThat(jeAfter)
                .as("a single append-only reversing AR_RECEIPT entry must be posted on bounce")
                .isEqualTo(jeBefore + 1);

        // The reversing entry must be balanced and the exact inverse of the cash leg:
        // original DR Cash(1180) / CR AR(1180)  →  reversal DR AR(1180) / CR Cash(1180).
        var reversalLines = latestReceiptReversalLines(receipt.glEntryUid());
        assertGlBalanced(reversalLines);
        assertThat(accountDebit(reversalLines, "1200"))
                .as("reversal must DR AR-control 1200 by the receipt cash amount").isEqualByComparingTo(AMOUNT);
        assertThat(accountCredit(reversalLines, "1000"))
                .as("reversal must CR Cash 1000 by the receipt cash amount").isEqualByComparingTo(AMOUNT);

        // (2) Invoice outstanding restored to the full face; status back to OPEN.
        var refreshedInv = arInvoiceRepo.findByUid(inv.uid()).orElseThrow();
        assertThat(refreshedInv.getOutstandingAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(refreshedInv.getStatus()).isEqualTo(ArInvoiceStatus.OPEN);

        // (3) reversed_at stamped on the receipt (the original journal is never mutated).
        ArReceipt reversedReceipt = arReceiptRepo.findByUid(receipt.uid()).orElseThrow();
        assertThat(reversedReceipt.getReversedAt())
                .as("ar_receipts.reversed_at must be stamped after a bounce reversal").isNotNull();
    }

    // =========================================================================
    // Bar 2: idempotency — a second BOUNCED dispatch must not double-reverse
    // =========================================================================

    @Test
    void bounce_secondDispatch_isIdempotent_noDoubleReversal() {
        ArInvoiceDto inv = openItem(AMOUNT);
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, customerUid, AMOUNT, TZS, LocalDate.now(), "CHEQUE", null, List.of()));

        Cheque cheque = depositedInboundCheque(receipt.uid());

        chequeService.bounce(cheque.getUid(), "Insufficient funds");
        dispatchBounceEvent(cheque.getUid());
        setContext();

        long jeAfterFirst = countReceiptJournalEntries();
        BigDecimal outstandingAfterFirst =
                arInvoiceRepo.findByUid(inv.uid()).orElseThrow().getOutstandingAmount();

        // Re-dispatch the SAME bounce event. No double-reversal may occur: the handler's
        // IdempotencyGuard (already-processed short-circuit) and the dispatcher's PENDING claim both
        // prevent reprocessing — and the receipt is already stamped reversed_at as a third guard.
        dispatchBounceEvent(cheque.getUid());
        setContext();

        assertThat(countReceiptJournalEntries())
                .as("re-dispatching the same BOUNCED event must NOT post a second reversal")
                .isEqualTo(jeAfterFirst);
        assertThat(arInvoiceRepo.findByUid(inv.uid()).orElseThrow().getOutstandingAmount())
                .as("invoice outstanding must not be restored twice")
                .isEqualByComparingTo(outstandingAfterFirst);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ArInvoiceDto openItem(BigDecimal amount) {
        return openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, customerUid, amount, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30), null));
    }

    /** Builds + saves an INBOUND cheque (linked to the receipt) directly in DEPOSITED state. */
    private Cheque depositedInboundCheque(String arReceiptUid) {
        CashBankAccount account = cashAccountRepo.findByCompanyIdAndIsDefaultTrue(company.getId())
                .orElseThrow(() -> new AssertionError("default cash account not seeded"));
        Cheque cheque = new Cheque(
                company.getId(), branch.getId(), account.getId(),
                "INB-" + System.nanoTime(), "Bounce Customer", AMOUNT, TZS,
                LocalDate.now(), LocalDate.now(),
                arReceiptUid, rootId);
        cheque.setStatus(ChequeStatus.DEPOSITED);
        return chequeRepo.save(cheque);
    }

    /** Finds the CHEQUE.BOUNCED outbox event for the cheque and dispatches it through the proxy. */
    private void dispatchBounceEvent(String chequeUid) {
        DomainEvent event = domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.CHEQUE_BOUNCED.equals(e.getEventType())
                          && chequeUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No CHEQUE.BOUNCED event found for cheque uid=" + chequeUid));
        dispatcher.dispatchOne(event.getId());
    }

    private long countReceiptJournalEntries() {
        return journalEntryRepo
                .findByCompanyId(company.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(e -> e.getSourceType() == JournalSourceType.AR_RECEIPT)
                .count();
    }

    /**
     * Returns the lines of the most-recently-posted AR_RECEIPT entry that is NOT the original
     * receipt entry — i.e. the append-only reversal.
     */
    private List<com.erp.modules.gl.domain.entity.JournalLine> latestReceiptReversalLines(
            String originalGlEntryUid) {
        var original = journalEntryRepo.findByUid(originalGlEntryUid).orElseThrow();
        var reversal = journalEntryRepo
                .findByCompanyId(company.getId(), org.springframework.data.domain.Pageable.unpaged())
                .stream()
                .filter(e -> e.getSourceType() == JournalSourceType.AR_RECEIPT)
                .filter(e -> !e.getId().equals(original.getId()))
                .max(java.util.Comparator.comparing(com.erp.modules.gl.domain.entity.JournalEntry::getId))
                .orElseThrow(() -> new AssertionError("no reversal AR_RECEIPT entry found"));
        return journalLineRepo.findByEntryIdOrderByLineNo(reversal.getId());
    }

    private void assertGlBalanced(List<com.erp.modules.gl.domain.entity.JournalLine> lines) {
        BigDecimal sumDr = lines.stream()
                .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCr = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sumDr).as("reversal entry must be balanced (ΣDR == ΣCR)")
                .isEqualByComparingTo(sumCr);
    }

    private BigDecimal accountDebit(List<com.erp.modules.gl.domain.entity.JournalLine> lines,
                                    String code) {
        Long id = accountId(code);
        return lines.stream().filter(l -> id.equals(l.getAccountId()))
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal accountCredit(List<com.erp.modules.gl.domain.entity.JournalLine> lines,
                                     String code) {
        Long id = accountId(code);
        return lines.stream().filter(l -> id.equals(l.getAccountId()))
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Long accountId(String code) {
        return accountRepo.findByCompanyIdAndAccountCode(company.getId(), code)
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Account " + code + " not seeded"));
    }
}
