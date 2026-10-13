package com.erp.modules.tax.service;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.tax.domain.dto.WhtPaymentResultDto;
import com.erp.modules.tax.domain.dto.WhtPeriodPaymentRequest;
import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtRemitRequest;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.money.CurrencyMinorUnits;
import com.erp.platform.common.time.CompanyCalendar;
import java.math.RoundingMode;
import java.time.YearMonth;
import com.erp.modules.tax.domain.dto.WhtRegisterRowDto;
import com.erp.modules.tax.domain.entity.WhtTransaction;
import com.erp.modules.tax.domain.enums.WhtKind;
import com.erp.modules.tax.repository.WhtTransactionRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WHT period register (ADR-0017 D-9, FR-WHT-04).
 */
@Service
@Transactional(readOnly = true)
public class WhtRegisterServiceImpl implements WhtRegisterService {

    private final WhtTransactionRepository whtTransactions;
    private final ScopeGuard              scopeGuard;
    private final NamedParameterJdbcTemplate jdbc;

    /**
     * The name AP payments stamped on every WHT certificate before 2026-10 instead of the
     * supplier's — see {@link #supplierNames}.
     */
    static final String PLACEHOLDER_SUPPLIER_NAME = "Supplier";

    private final TaxPaymentPoster        paymentPoster;
    private final CurrencyMinorUnits      minorUnits;
    private final AuditService            audit;
    private final CompanyCalendar         calendar;

    public WhtRegisterServiceImpl(WhtTransactionRepository whtTransactions,
                                   ScopeGuard scopeGuard,
                                   JdbcTemplate jdbc,
                                   TaxPaymentPoster paymentPoster,
                                   CurrencyMinorUnits minorUnits,
                                   AuditService audit,
                                   CompanyCalendar calendar) {
        this.whtTransactions = whtTransactions;
        this.scopeGuard      = scopeGuard;
        this.jdbc            = new NamedParameterJdbcTemplate(jdbc);
        this.paymentPoster   = paymentPoster;
        this.minorUnits      = minorUnits;
        this.audit           = audit;
        this.calendar        = calendar;
    }

    @Override
    public WhtRegisterDto getRegister(Long companyId, LocalDate periodStart, LocalDate periodEnd) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        List<WhtTransaction> payableRows = whtTransactions
                .findByCompanyIdAndKindAndCertificateDateBetween(
                        companyId, WhtKind.WHT_ON_PAYMENT, periodStart, periodEnd);

        List<WhtTransaction> receivableRows = whtTransactions
                .findByCompanyIdAndKindAndCertificateDateBetween(
                        companyId, WhtKind.WHT_ON_RECEIPT, periodStart, periodEnd);

        Map<Long, String> names = supplierNames(companyId, payableRows);
        List<WhtRegisterRowDto> payableDtos = payableRows.stream()
                .map(t -> toRow(t, names))
                .toList();
        List<WhtRegisterRowDto> receivableDtos = receivableRows.stream()
                .map(t -> toRow(t, Map.of()))
                .toList();

        BigDecimal totalPayable = payableRows.stream()
                .map(WhtTransaction::getWhtAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReceivable = receivableRows.stream()
                .map(WhtTransaction::getWhtAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return new WhtRegisterDto(companyId, periodStart, periodEnd,
                payableDtos, totalPayable,
                receivableDtos, totalReceivable);
    }

    @Override
    @Transactional
    public void markRemitted(String whtTransactionUid, String remittancePeriod, String remittanceRef) {
        remit(whtTransactionUid, new WhtRemitRequest(remittancePeriod, remittanceRef));
    }

    @Override
    @Transactional
    public WhtPaymentResultDto remit(String whtTransactionUid, WhtRemitRequest req) {
        WhtTransaction txn = whtTransactions.findByUid(whtTransactionUid)
                .orElseThrow(() -> new NotFoundException("WHT transaction not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), txn.getCompanyId());
        if (txn.isRemitted()) {
            // WHT transaction uid not exposed in the message
            throw new ConflictException(
                    "This WHT transaction has already been marked as remitted.");
        }
        boolean book = req.cashBankAccountUid() != null && !req.cashBankAccountUid().isBlank();
        BigDecimal paid = BigDecimal.ZERO;
        String cashTxnUid = null;
        if (book) {
            // ACC-07: book the remittance - DR WHT Payable / CR the cash/bank account.
            if (txn.getKind() != WhtKind.WHT_ON_PAYMENT) {
                throw new ConflictException(
                        "WHT deducted by a customer is not paid to TRA. Only WHT deducted from"
                        + " supplier payments can be paid from a bank account.");
            }
            paid = baseAmount(txn, baseScale(txn.getCompanyId()));
            LocalDate date = req.paymentDate() != null
                    ? req.paymentDate() : calendar.today(txn.getCompanyId());
            cashTxnUid = paymentPoster.pay(txn.getCompanyId(), GlConfigKey.WHT_PAYABLE,
                    req.cashBankAccountUid(), paid, date,
                    "WHT payment " + txn.getWhtNumber() + " - " + req.remittanceRef()).uid();
        }
        stampRemitted(txn, req.remittancePeriod(), req.remittanceRef());
        audit.record(AuditEvent.of(AuditActions.WHT_REMIT, "wht_transactions",
                        txn.getId(), txn.getUid())
                .detail(Map.of("certificates", "1",
                        "amountPaid", paid.toPlainString(),
                        "booked", String.valueOf(book))));
        return new WhtPaymentResultDto(1, paid, cashTxnUid);
    }

    @Override
    @Transactional
    public WhtPaymentResultDto payPeriod(WhtPeriodPaymentRequest req) {
        Long companyId = req.companyId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (req.periodEnd().isBefore(req.periodStart())) {
            throw new IllegalArgumentException("The period end must be on or after its start.");
        }
        List<WhtTransaction> open = whtTransactions
                .findByCompanyIdAndKindAndCertificateDateBetween(
                        companyId, WhtKind.WHT_ON_PAYMENT, req.periodStart(), req.periodEnd())
                .stream()
                .filter(t -> !t.isRemitted())
                .toList();
        if (open.isEmpty()) {
            throw new ConflictException(
                    "There is no unpaid WHT deducted from suppliers in this period.");
        }
        int scale = baseScale(companyId);
        BigDecimal total = open.stream()
                .map(t -> baseAmount(t, scale))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String period = YearMonth.from(req.periodStart()).toString();
        String cashTxnUid = paymentPoster.pay(companyId, GlConfigKey.WHT_PAYABLE,
                req.cashBankAccountUid(), total, req.paymentDate(),
                "WHT payment " + req.periodStart() + " to " + req.periodEnd()
                        + " - " + req.remittanceRef()).uid();
        for (WhtTransaction t : open) {
            stampRemitted(t, period, req.remittanceRef());
        }
        audit.record(AuditEvent.of(AuditActions.WHT_REMIT, "wht_transactions", null, null)
                .detail(Map.of("certificates", String.valueOf(open.size()),
                        "amountPaid", total.toPlainString(),
                        "period", req.periodStart() + ".." + req.periodEnd(),
                        "cashTransactionUid", cashTxnUid)));
        return new WhtPaymentResultDto(open.size(), total, cashTxnUid);
    }

    private void stampRemitted(WhtTransaction txn, String remittancePeriod, String remittanceRef) {
        txn.setRemitted(true);
        txn.setRemittancePeriod(remittancePeriod);
        txn.setRemittanceRef(remittanceRef);
        txn.setRemittedAt(Instant.now());
        txn.setRemittedBy(actorId());
        whtTransactions.save(txn);
    }

    /**
     * The certificate's WHT in base currency - exactly what the AP payment credited to WHT Payable:
     * {@code wht_amount x ap_payments.fx_rate}, HALF_UP to the base minor units
     * (ApPaymentServiceImpl). Scalar SQL, company-scoped: the tax module never imports an AP entity.
     */
    private BigDecimal baseAmount(WhtTransaction t, int baseScale) {
        BigDecimal rate = jdbc.query("""
                SELECT fx_rate FROM ap_payments WHERE uid = :uid AND company_id = :companyId
                """,
                new MapSqlParameterSource("uid", t.getSourceRef())
                        .addValue("companyId", t.getCompanyId()),
                rs -> rs.next() ? rs.getBigDecimal(1) : null);
        BigDecimal r = rate != null ? rate : BigDecimal.ONE;
        return t.getWhtAmount().multiply(r).setScale(baseScale, RoundingMode.HALF_UP);
    }

    /** Minor units of the company's base currency (0 for TZS), from the currencies master. */
    private int baseScale(Long companyId) {
        String base = jdbc.query("SELECT base_currency FROM companies WHERE id = :id",
                new MapSqlParameterSource("id", companyId),
                rs -> rs.next() ? rs.getString(1) : null);
        return minorUnits.of(base);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    /**
     * Live supplier names for the certificates that were stamped with the literal placeholder
     * "Supplier" (every AP-payment WHT row before 2026-10). The certificate keeps its stored name
     * otherwise — it is a snapshot of who was certified — but a placeholder names nobody, so the
     * register reads the supplier it points at ({@code party_id}, company-scoped) instead. That
     * fixes the history on screen and in the export without a data repair. Scalar native SQL: the
     * tax module never imports a parties entity.
     */
    private Map<Long, String> supplierNames(Long companyId, List<WhtTransaction> rows) {
        Set<Long> ids = rows.stream()
                .filter(WhtRegisterServiceImpl::hasPlaceholderName)
                .map(WhtTransaction::getPartyId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, String> names = new HashMap<>();
        jdbc.query("""
                SELECT id, display_name FROM suppliers
                WHERE company_id = :companyId AND id IN (:ids)
                """,
                new MapSqlParameterSource("companyId", companyId).addValue("ids", ids),
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("display_name"));
                });
        return names;
    }

    private static boolean hasPlaceholderName(WhtTransaction t) {
        return "SUPPLIER".equals(t.getPartyKind())
                && PLACEHOLDER_SUPPLIER_NAME.equals(t.getPartyName());
    }

    private WhtRegisterRowDto toRow(WhtTransaction t, Map<Long, String> liveSupplierNames) {
        String name = t.getPartyName();
        if (hasPlaceholderName(t)) {
            String live = liveSupplierNames.get(t.getPartyId());
            if (live != null && !live.isBlank()) {
                name = live;
            }
        }
        return new WhtRegisterRowDto(
                t.getWhtNumber(),
                t.getKind(),
                t.getPartyKind(),
                name,
                t.getSourceRef(),
                t.getTaxableBase(),
                t.getWhtAmount(),
                t.getCertificateDate(),
                t.getUid(),
                t.isRemitted());
    }
}
