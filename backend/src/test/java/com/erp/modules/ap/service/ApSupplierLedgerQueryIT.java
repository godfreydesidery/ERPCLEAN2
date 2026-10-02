package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.ApSupplierLedgerDto;
import com.erp.modules.ap.domain.dto.ApSupplierLedgerRowDto;
import com.erp.modules.ap.domain.enums.ApLedgerEntryType;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
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
 * {@link ApSupplierLedgerQuery} against real Postgres: the UNION of bills / payments / payment-run
 * shares / reversals / debit notes must bind and execute; DRAFT and HELD bills (not on the ledger)
 * must be left off; a payment run that spans suppliers must count only THIS supplier's allocations;
 * the balance brought forward, running balance and closing must follow; and another company's
 * supplier, or a caller outside the company, must be refused.
 */
class ApSupplierLedgerQueryIT extends PostgresIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private AppUserRepository      users;
    @Autowired private PasswordEncoder        passwordEncoder;
    @Autowired private IamTestData            testData;
    @Autowired private SupplierService        supplierService;
    @Autowired private JdbcTemplate           jdbc;

    @Autowired private ApSupplierLedgerQuery  ledgerQuery;

    private Company company;
    private Company otherCompany;
    private AppUser root;
    private SupplierDto supplier;
    private SupplierDto otherSupplier;
    private SupplierDto foreignSupplier;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("ApLedger IT Org"));
        company      = companies.save(new Company(org, "APLG", "ApLedger IT Co"));
        otherCompany = companies.save(new Company(org, "APLX", "ApLedger Other Co"));

        AppUser u = new AppUser("apledger_root", passwordEncoder.encode("ApLedger@1!Xx"), "Root");
        u.setRoot(true);
        u.setOrganisationId(org.getId());
        root = users.save(u);

        actAs(root, company);
        supplier      = newSupplier(company, "Mbasha Holdings");
        otherSupplier = newSupplier(company, "Other Supplier");
        actAs(root, otherCompany);
        foreignSupplier = newSupplier(otherCompany, "Foreign Supplier");
        actAs(root, company);

        Long c = company.getId();
        Long s = supplier.id();
        // Before the period: opening 1,000, paid 300 → b/f 700.
        bill(c, s, "OPENING_BALANCE", "OB-1", "OB", "1000", "MATCHED", LocalDate.of(2026, 8, 1));
        payment(c, s, "PAY-0", "300", null, LocalDate.of(2026, 8, 20), null);
        // In the period.
        Long b1 = bill(c, s, "BILL", "BILL-1", "SI-1", "600", "APPROVED", LocalDate.of(2026, 9, 2));
        // Not on the ledger: a HELD bill (variance not accepted) and a DRAFT.
        bill(c, s, "BILL", "BILL-HELD", "SI-2", "999", "HELD", LocalDate.of(2026, 9, 4));
        bill(c, s, "BILL", null, "SI-3", "777", "DRAFT", LocalDate.of(2026, 9, 4));
        // A cheque payment with WHT, bounced on the 15th.
        payment(c, s, "PAY-1", "200", "10", LocalDate.of(2026, 9, 10),
                OffsetDateTime.parse("2026-09-15T08:00:00Z"));
        // A payment run across two suppliers: 250 of its 900 is this supplier's.
        Long ob = bill(c, otherSupplier.id(), "BILL", "BILL-O", "SO-1", "650", "PAID",
                LocalDate.of(2026, 9, 1));
        Long run = payment(c, null, "PAY-RUN", "900", null, LocalDate.of(2026, 9, 18), null);
        allocate(c, run, b1, "250");
        allocate(c, run, ob, "650");
        debitNote(c, s, "DN-1", "40", LocalDate.of(2026, 9, 22));
        // Another company's supplier: never seen.
        bill(otherCompany.getId(), foreignSupplier.id(), "BILL", "BILL-X", "SX", "555", "APPROVED",
                LocalDate.of(2026, 9, 5));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void periodStatement_onlyPostedBills_runShareOnly_runningBalanceToClosing() {
        ApSupplierLedgerDto dto = ledgerQuery.ledger(company.getId(), null, supplier.uid(),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);

        assertThat(dto.supplierName()).isEqualTo("Mbasha Holdings");
        assertThat(dto.openingBalance()).isEqualByComparingTo("700");

        List<ApSupplierLedgerRowDto> rows = dto.rows();
        assertThat(rows).extracting(ApSupplierLedgerRowDto::type).containsExactly(
                ApLedgerEntryType.BILL, ApLedgerEntryType.PAYMENT,
                ApLedgerEntryType.PAYMENT_REVERSAL, ApLedgerEntryType.PAYMENT,
                ApLedgerEntryType.DEBIT_NOTE);
        assertThat(rows).extracting(r -> r.balance().stripTrailingZeros().toPlainString())
                .containsExactly("1300", "1100", "1300", "1050", "1010");
        assertThat(rows.get(0).description()).contains("supplier invoice SI-1");
        assertThat(rows.get(1).description()).contains("of which WHT withheld 10.00");
        assertThat(rows.get(2).date()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(rows.get(3).reference()).isEqualTo("PAY-RUN");
        assertThat(rows.get(3).debit()).isEqualByComparingTo("250");

        assertThat(dto.totalDebit()).isEqualByComparingTo("490");
        assertThat(dto.totalCredit()).isEqualByComparingTo("800");
        assertThat(dto.closingBalance()).isEqualByComparingTo("1010");
        assertThat(dto.otherCurrencyCount()).isZero();
    }

    @Test
    void withoutFromDate_runsFromTheOpeningBalance_toTheSameClosing() {
        ApSupplierLedgerDto dto = ledgerQuery.ledger(company.getId(), supplier.id(), null,
                null, LocalDate.of(2026, 9, 30), null);

        assertThat(dto.rows().get(0).type()).isEqualTo(ApLedgerEntryType.OPENING_BALANCE);
        assertThat(dto.rows()).hasSize(7);
        assertThat(dto.closingBalance()).isEqualByComparingTo("1010");
    }

    @Test
    void aSupplierOfAnotherCompany_isNotFound() {
        assertThatThrownBy(() -> ledgerQuery.ledger(company.getId(), null, foreignSupplier.uid(),
                null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> ledgerQuery.resolveSupplier(company.getId(), foreignSupplier.id(), null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aNonRootCallerOutsideTheCompany_isRefused() {
        AppUser clerk = new AppUser("apledger_clerk", passwordEncoder.encode("ApLedger@1!Xx"), "Clerk");
        clerk.setOrganisationId(company.getOrganisation().getId());
        clerk = users.save(clerk);
        testData.seedMembership(clerk.getUid(), otherCompany.getUid());
        actAs(clerk, otherCompany);

        assertThatThrownBy(() -> ledgerQuery.ledger(company.getId(), null, supplier.uid(),
                null, null, null)).isInstanceOf(ForbiddenException.class);
    }

    // -------------------------------------------------------------------------

    // ---- Live-test defects 1 + 2 (supplier side) ---------------------------------------------

    @Test
    void everyPostedBill_isReferencedByItsBillNumber() {
        // chk_supplier_bill_number_when_posted guarantees a number on every bill on the ledger.
        ApSupplierLedgerDto dto = ledgerQuery.ledger(company.getId(), null, supplier.uid(),
                null, LocalDate.of(2026, 9, 30), "TZS");
        assertThat(dto.rows()).filteredOn(r -> r.type() == ApLedgerEntryType.BILL
                        || r.type() == ApLedgerEntryType.OPENING_BALANCE)
                .extracting(ApSupplierLedgerRowDto::reference).containsExactly("OB-1", "BILL-1");
    }

    @Test
    void noCurrencyNamed_oneSectionPerCurrency_supplierDefaultFirst() {
        jdbc.update("""
                INSERT INTO supplier_bills (uid, company_id, supplier_id, bill_number,
                    supplier_invoice_no, source, bill_date, due_date, net_amount, gross_amount,
                    outstanding_amount, currency, status)
                VALUES (?, ?, ?, 'BILL-USD', 'SI-USD', 'BILL', ?, ?, 400, 400, 400, 'USD', 'APPROVED')
                """, uid(), company.getId(), supplier.id(), LocalDate.of(2026, 9, 8),
                LocalDate.of(2026, 10, 8));

        List<ApSupplierLedgerDto> sections = ledgerQuery.statements(company.getId(), null,
                supplier.uid(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);
        assertThat(sections).extracting(ApSupplierLedgerDto::currency).containsExactly("TZS", "USD");
        assertThat(sections.get(0).closingBalance()).isEqualByComparingTo("1010");
        assertThat(sections.get(1).closingBalance()).isEqualByComparingTo("400");

        jdbc.update("UPDATE suppliers SET default_currency = 'USD' WHERE id = ?", supplier.id());
        assertThat(ledgerQuery.statements(company.getId(), null, supplier.uid(),
                null, LocalDate.of(2026, 9, 30), null))
                .extracting(ApSupplierLedgerDto::currency).containsExactly("USD", "TZS");
        assertThat(ledgerQuery.ledger(company.getId(), null, supplier.uid(),
                null, LocalDate.of(2026, 9, 30), null).currency()).isEqualTo("USD");
    }

    private void actAs(AppUser user, Company c) {
        RequestContext.set(new RequestContext.Principal(user.getId(), user.getUsername(), user.isRoot(),
                c.getId(), null, null, c.getOrganisation().getId()));
    }

    private SupplierDto newSupplier(Company c, String name) {
        return supplierService.create(new CreateSupplierRequest(
                c.getId(), PartyType.INDIVIDUAL, name, null, null, null, null, null, null, null, null,
                null, null, null, null, SupplierKind.GOODS, null, null));
    }

    private static String uid() {
        return String.format("APLEDGERIT%016d", SEQ.incrementAndGet());
    }

    private Long bill(Long companyId, Long supplierId, String source, String billNo, String supplierInv,
                      String gross, String status, LocalDate date) {
        return jdbc.queryForObject("""
                INSERT INTO supplier_bills (uid, company_id, supplier_id, bill_number,
                    supplier_invoice_no, source, bill_date, due_date, net_amount, gross_amount,
                    outstanding_amount, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'TZS', ?) RETURNING id
                """, Long.class, uid(), companyId, supplierId, billNo, supplierInv, source, date,
                date.plusDays(30), new BigDecimal(gross), new BigDecimal(gross), BigDecimal.ZERO, status);
    }

    private Long payment(Long companyId, Long supplierId, String number, String amount, String wht,
                         LocalDate date, OffsetDateTime reversedAt) {
        return jdbc.queryForObject("""
                INSERT INTO ap_payments (uid, company_id, supplier_id, payment_number, kind,
                    payment_date, amount, currency, tender_type, bank_reference, wht_amount, reversed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TZS', 'BANK_TRANSFER', 'TT-1', ?, ?) RETURNING id
                """, Long.class, uid(), companyId, supplierId, number,
                supplierId == null ? "PAYMENT_RUN" : "SINGLE", date, new BigDecimal(amount),
                wht != null ? new BigDecimal(wht) : null, reversedAt);
    }

    private void allocate(Long companyId, Long paymentId, Long billId, String amount) {
        jdbc.update("""
                INSERT INTO ap_payment_allocations (company_id, ap_payment_id, supplier_bill_id,
                    allocated_amount) VALUES (?, ?, ?, ?)
                """, companyId, paymentId, billId, new BigDecimal(amount));
    }

    private void debitNote(Long companyId, Long supplierId, String number, String amount, LocalDate date) {
        jdbc.update("""
                INSERT INTO ap_debit_notes (uid, company_id, supplier_id, debit_note_number,
                    note_date, amount, net_amount, currency, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TZS', 'Short delivery')
                """, uid(), companyId, supplierId, number, date, new BigDecimal(amount),
                new BigDecimal(amount));
    }
}
