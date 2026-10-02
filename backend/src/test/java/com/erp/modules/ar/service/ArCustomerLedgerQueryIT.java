package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ArCustomerLedgerDto;
import com.erp.modules.ar.domain.dto.ArCustomerLedgerRowDto;
import com.erp.modules.ar.domain.enums.ArLedgerEntryType;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * {@link ArCustomerLedgerQuery} against real Postgres: the UNION of invoices / receipts /
 * reversals / credit notes / write-offs must bind and execute (native SQL passes unit tests even
 * when broken), the balance brought forward must be everything before the period, the running
 * balance must follow the movements, a bounced cheque must land on its LOCAL reversal date, other
 * currencies must be counted and left off — and a customer of another company, or a caller who is
 * not a member of the company, must be refused.
 *
 * <p>The AR rows are inserted directly: the query reads the tables, and driving a sale + receipt +
 * cheque bounce + credit note + write-off through their services would test those services, not
 * this read.
 */
class ArCustomerLedgerQueryIT extends PostgresIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;
    @Autowired private CustomerService        customerService;
    @Autowired private JdbcTemplate           jdbc;

    @Autowired private ArCustomerLedgerQuery  ledgerQuery;

    private Company company;
    private Company otherCompany;
    private AppUser root;
    private CustomerDto customer;
    private CustomerDto foreignCustomer;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("ArLedger IT Org"));
        company      = companies.save(new Company(org, "ARLG", "ArLedger IT Co"));
        otherCompany = companies.save(new Company(org, "ARLX", "ArLedger Other Co"));

        AppUser u = new AppUser("arledger_root", passwordEncoder.encode("ArLedger@1!Xx"), "Root");
        u.setRoot(true);
        u.setOrganisationId(org.getId());
        root = users.save(u);

        actAs(root, company);
        customer = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.BUSINESS, "Duka la Mama", null, "111-222-333", null, null,
                null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        actAs(root, otherCompany);
        foreignCustomer = customerService.create(new CreateCustomerRequest(
                otherCompany.getId(), PartyType.INDIVIDUAL, "Foreign Duka", null, null, null, null,
                null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        actAs(root, company);

        Long c = company.getId();
        Long cust = customer.id();
        // Before the period: opening balance 1,000 and a receipt of 400 → b/f 600.
        invoice(c, cust, "OPENING_BALANCE", "OB-1", "1000", "TZS", LocalDate.of(2026, 8, 1));
        receipt(c, cust, "RC-0", "400", "TZS", LocalDate.of(2026, 8, 15), null);
        // In the period.
        Long inv1 = invoice(c, cust, "SALE", "INV-1", "500", "TZS", LocalDate.of(2026, 9, 3));
        // A cheque receipt that bounced: reversed at 21:30 UTC on the 12th = 00:30 on the 13th in Dar.
        receipt(c, cust, "RC-1", "300", "TZS", LocalDate.of(2026, 9, 9),
                OffsetDateTime.parse("2026-09-12T21:30:00Z"));
        creditNote(c, cust, "CN-1", "50", LocalDate.of(2026, 9, 20));
        writeOff(c, cust, inv1, "25", LocalDate.of(2026, 9, 25));
        // Another currency: counted, not summed.
        invoice(c, cust, "SALE", "INV-USD", "200", "USD", LocalDate.of(2026, 9, 5));
        // After the period: left off.
        invoice(c, cust, "SALE", "INV-LATE", "999", "TZS", LocalDate.of(2026, 10, 5));
        // Another company's customer: never seen.
        invoice(otherCompany.getId(), foreignCustomer.id(), "SALE", "INV-X", "777", "TZS",
                LocalDate.of(2026, 9, 10));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void periodStatement_bringsForwardEarlierMovements_andRunsTheBalanceThroughThePeriod() {
        ArCustomerLedgerDto dto = ledgerQuery.ledger(company.getId(), null, customer.uid(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);

        assertThat(dto.currency()).isEqualTo("TZS");
        assertThat(dto.customerName()).isEqualTo("Duka la Mama");
        assertThat(dto.customerTin()).isEqualTo("111-222-333");
        assertThat(dto.company().name()).isEqualTo("ArLedger IT Co");
        assertThat(dto.openingBalance()).isEqualByComparingTo("600");

        List<ArCustomerLedgerRowDto> rows = dto.rows();
        assertThat(rows).extracting(ArCustomerLedgerRowDto::type).containsExactly(
                ArLedgerEntryType.INVOICE, ArLedgerEntryType.RECEIPT,
                ArLedgerEntryType.RECEIPT_REVERSAL, ArLedgerEntryType.CREDIT_NOTE,
                ArLedgerEntryType.WRITE_OFF);
        assertThat(rows).extracting(r -> r.balance().stripTrailingZeros().toPlainString())
                .containsExactly("1100", "800", "1100", "1050", "1025");
        // The reversal lands on its LOCAL date (Africa/Dar_es_Salaam), not the UTC one.
        assertThat(rows.get(2).date()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(rows.get(2).reference()).isEqualTo("RC-1");
        // The write-off references the invoice it wrote off.
        assertThat(rows.get(4).reference()).isEqualTo("INV-1");

        assertThat(dto.totalDebit()).isEqualByComparingTo("800");
        assertThat(dto.totalCredit()).isEqualByComparingTo("375");
        assertThat(dto.closingBalance()).isEqualByComparingTo("1025");
        assertThat(dto.otherCurrencyCount()).isEqualTo(1);
    }

    @Test
    void withoutFromDate_runsFromTheFirstMovement_andArrivesAtTheSameClosing() {
        ArCustomerLedgerDto dto = ledgerQuery.ledger(company.getId(), customer.id(), null,
                null, LocalDate.of(2026, 9, 30), null);

        assertThat(dto.openingBalance()).isEqualByComparingTo("0");
        assertThat(dto.rows()).hasSize(7);
        assertThat(dto.rows().get(0).type()).isEqualTo(ArLedgerEntryType.OPENING_BALANCE);
        assertThat(dto.closingBalance()).isEqualByComparingTo("1025");
    }

    @Test
    void theOtherCurrency_isItsOwnStatement() {
        ArCustomerLedgerDto dto = ledgerQuery.ledger(company.getId(), null, customer.uid(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), "usd");

        assertThat(dto.currency()).isEqualTo("USD");
        assertThat(dto.rows()).hasSize(1);
        assertThat(dto.closingBalance()).isEqualByComparingTo("200");
    }

    @Test
    void aCustomerOfAnotherCompany_isNotFound_byUidOrById() {
        assertThatThrownBy(() -> ledgerQuery.ledger(company.getId(), null, foreignCustomer.uid(),
                null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> ledgerQuery.ledger(company.getId(), foreignCustomer.id(), null,
                null, null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNonRootCallerOutsideTheCompany_isRefused() {
        AppUser clerk = new AppUser("arledger_clerk", passwordEncoder.encode("ArLedger@1!Xx"), "Clerk");
        clerk.setOrganisationId(company.getOrganisation().getId());
        clerk = users.save(clerk);
        testData.seedMembership(clerk.getUid(), otherCompany.getUid());
        actAs(clerk, otherCompany);

        assertThatThrownBy(() -> ledgerQuery.ledger(company.getId(), null, customer.uid(),
                null, null, null)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aStartAfterTheEnd_isAFriendlyBadRequest() {
        assertThatThrownBy(() -> ledgerQuery.ledger(company.getId(), null, customer.uid(),
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 9, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("The start date must be on or before the end date.");
    }

    // -------------------------------------------------------------------------

    private void actAs(AppUser user, Company c) {
        RequestContext.set(new RequestContext.Principal(user.getId(), user.getUsername(), user.isRoot(),
                c.getId(), null, null, c.getOrganisation().getId()));
    }

    private static String uid() {
        return String.format("ARLEDGERIT%016d", SEQ.incrementAndGet());
    }

    private Long invoice(Long companyId, Long customerId, String source, String docNo, String amount,
                         String currency, LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO ar_invoices (uid, company_id, customer_id, source, document_no,
                    original_amount, outstanding_amount, currency, invoice_date, due_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'OPEN') RETURNING id
                """, Long.class, uid(), companyId, customerId, source, docNo,
                new BigDecimal(amount), new BigDecimal(amount), currency, date, date.plusDays(30));
    }

    private void receipt(Long companyId, Long customerId, String number, String amount,
                         String currency, LocalDate date, OffsetDateTime reversedAt) {
        jdbc.update("""
                INSERT INTO ar_receipts (uid, company_id, customer_id, receipt_number, receipt_date,
                    amount, unallocated_amount, currency, tender_type, reversed_at)
                VALUES (?, ?, ?, ?, ?, ?, 0, ?, 'CHEQUE', ?)
                """, uid(), companyId, customerId, number, date, new BigDecimal(amount), currency,
                reversedAt);
    }

    private void creditNote(Long companyId, Long customerId, String number, String amount,
                            LocalDate date) {
        jdbc.update("""
                INSERT INTO ar_credit_notes (uid, company_id, customer_id, credit_note_number,
                    note_date, amount, net_amount, currency, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TZS', 'Damaged goods')
                """, uid(), companyId, customerId, number, date, new BigDecimal(amount),
                new BigDecimal(amount));
    }

    private void writeOff(Long companyId, Long customerId, Long invoiceId, String amount,
                          LocalDate date) {
        jdbc.update("""
                INSERT INTO ar_write_offs (uid, company_id, customer_id, ar_invoice_id,
                    write_off_date, amount, currency, reason)
                VALUES (?, ?, ?, ?, ?, ?, 'TZS', 'Uncollectable')
                """, uid(), companyId, customerId, invoiceId, date, new BigDecimal(amount));
    }
}
