package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApAgeingRowDto;
import com.erp.modules.ap.domain.dto.ApSupplierAgeingRowDto;
import com.erp.modules.ap.domain.entity.ApDebitNote;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.repository.ApDebitNoteRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.ar.domain.enums.AgeingBucket;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes AP ageing on demand (ADR-0015 D-7, mirrors ArAgeingQuery).
 * Buckets: CURRENT, D1_30, D31_60, D61_90, D90_PLUS — same 5 as AR.
 *
 * <p>Amounts are in each bill's OWN currency, one five-bucket block per currency (as AR): a USD
 * bill is never added to TZS ones under the TZS label. Not converted to base, for the AR reason:
 * V62 back-filled pre-existing bills' base figures at rate 1 whatever their currency.
 *
 * <p>LBO-15: a debit note's UNAPPLIED remainder is netted as a credit (aged by its note date), so
 * the ageing total agrees with the supplier balance and statement. Payments not yet allocated to a
 * bill stay out of the buckets, as before.
 *
 * <p>AP-11: {@link #supplierAgeing} is the company-wide creditors ageing - one row per supplier and
 * currency. Supplier names are read by SQL (no parties entity import), like the statement query.
 */
@Component
@Transactional(readOnly = true)
public class ApAgeingQuery {

    private final SupplierBillRepository     bills;
    private final ApDebitNoteRepository      debitNotes;
    private final CompanyRepository          companies;
    private final ScopeGuard                 scopeGuard;
    private final NamedParameterJdbcTemplate jdbc;

    public ApAgeingQuery(SupplierBillRepository bills,
                          ApDebitNoteRepository debitNotes,
                          CompanyRepository companies,
                          ScopeGuard scopeGuard,
                          NamedParameterJdbcTemplate jdbc) {
        this.bills      = bills;
        this.debitNotes = debitNotes;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
        this.jdbc       = jdbc;
    }

    /**
     * Ageing breakdown as at a given date - for one supplier, or (supplierId null) the whole
     * company's creditors as a five-bucket summary per currency.
     */
    public List<ApAgeingRowDto> ageing(Long companyId, Long supplierId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        List<SupplierBill> openItems = supplierId != null
                ? bills.findOpenForStatement(companyId, supplierId)
                : bills.findOpenForCompany(companyId);
        List<ApDebitNote> credits = supplierId != null
                ? debitNotes.findUnappliedBySupplier(companyId, supplierId)
                : debitNotes.findUnappliedByCompany(companyId);

        // One five-bucket block PER CURRENCY (base first, always present; then each foreign
        // currency A to Z). A bill is aged in its own currency and never added to another one.
        Map<String, Map<AgeingBucket, BigDecimal>> byCurrency = new TreeMap<>(baseFirst(currency));
        byCurrency.put(currency, zeroBuckets());
        for (SupplierBill bill : openItems) {
            AgeingBucket bucket = classify(bill.getDueDate(), asAt);
            String ccy = bill.getCurrency() != null ? bill.getCurrency().value() : currency;
            byCurrency.computeIfAbsent(ccy, c -> zeroBuckets())
                    .merge(bucket, bill.getOutstandingAmount(), BigDecimal::add);
        }
        for (ApDebitNote note : credits) {
            String ccy = note.getCurrency() != null ? note.getCurrency().value() : currency;
            byCurrency.computeIfAbsent(ccy, c -> zeroBuckets())
                    .merge(classify(note.getNoteDate(), asAt), note.getUnappliedAmount().negate(),
                            BigDecimal::add);
        }

        List<ApAgeingRowDto> rows = new ArrayList<>();
        for (Map.Entry<String, Map<AgeingBucket, BigDecimal>> e : byCurrency.entrySet()) {
            for (AgeingBucket bk : AgeingBucket.values()) {
                rows.add(new ApAgeingRowDto(bk, e.getValue().get(bk), e.getKey()));
            }
        }
        return rows;
    }

    /**
     * Company-wide creditors ageing (AP-11): one row per supplier and currency with an open
     * balance, each carrying the five buckets and a total, net of unapplied debit notes. Sorted by
     * supplier name, then code, then currency (base first).
     */
    public List<ApSupplierAgeingRowDto> supplierAgeing(Long companyId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        MapSqlParameterSource p = new MapSqlParameterSource("companyId", companyId);
        List<String> base = jdbc.queryForList(
                "SELECT base_currency FROM companies WHERE id = :companyId", p, String.class);
        String currency = !base.isEmpty() && base.get(0) != null ? base.get(0) : "TZS";

        Map<SupplierCurrency, Map<AgeingBucket, BigDecimal>> bySupplier = new LinkedHashMap<>();
        for (SupplierBill bill : bills.findOpenForCompany(companyId)) {
            String ccy = bill.getCurrency() != null ? bill.getCurrency().value() : currency;
            bySupplier.computeIfAbsent(new SupplierCurrency(bill.getSupplierId(), ccy),
                            k -> zeroBuckets())
                    .merge(classify(bill.getDueDate(), asAt), bill.getOutstandingAmount(),
                            BigDecimal::add);
        }
        for (ApDebitNote note : debitNotes.findUnappliedByCompany(companyId)) {
            String ccy = note.getCurrency() != null ? note.getCurrency().value() : currency;
            bySupplier.computeIfAbsent(new SupplierCurrency(note.getSupplierId(), ccy),
                            k -> zeroBuckets())
                    .merge(classify(note.getNoteDate(), asAt),
                            note.getUnappliedAmount().negate(), BigDecimal::add);
        }

        Map<Long, String[]> names = new HashMap<>();
        if (!bySupplier.isEmpty()) {
            p.addValue("ids", bySupplier.keySet().stream().map(SupplierCurrency::supplierId)
                    .distinct().toList());
            jdbc.query("SELECT id, uid, code, display_name FROM suppliers"
                            + " WHERE company_id = :companyId AND id IN (:ids)", p,
                    rs -> {
                        names.put(rs.getLong("id"), new String[]{rs.getString("uid"),
                                rs.getString("code"), rs.getString("display_name")});
                    });
        }

        List<ApSupplierAgeingRowDto> rows = new ArrayList<>();
        for (Map.Entry<SupplierCurrency, Map<AgeingBucket, BigDecimal>> e : bySupplier.entrySet()) {
            Map<AgeingBucket, BigDecimal> b = e.getValue();
            if (b.values().stream().allMatch(v -> v.signum() == 0)) {
                continue;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (BigDecimal v : b.values()) {
                total = total.add(v);
            }
            String[] n = names.getOrDefault(e.getKey().supplierId(), new String[3]);
            rows.add(new ApSupplierAgeingRowDto(e.getKey().supplierId(), n[0], n[1], n[2],
                    b.get(AgeingBucket.CURRENT), b.get(AgeingBucket.D1_30),
                    b.get(AgeingBucket.D31_60), b.get(AgeingBucket.D61_90),
                    b.get(AgeingBucket.D90_PLUS), total, e.getKey().currency()));
        }
        rows.sort(Comparator
                .comparing(ApSupplierAgeingRowDto::supplierName,
                        Comparator.nullsLast(String::compareToIgnoreCase))
                .thenComparing(ApSupplierAgeingRowDto::supplierCode,
                        Comparator.nullsLast(String::compareTo))
                .thenComparing(ApSupplierAgeingRowDto::currency, baseFirst(currency)));
        return rows;
    }

    private record SupplierCurrency(Long supplierId, String currency) {}

    // -------------------------------------------------------------------------

    /** Base currency first, then the others A to Z. */
    private static Comparator<String> baseFirst(String baseCurrency) {
        return Comparator.comparing((String c) -> !c.equals(baseCurrency))
                .thenComparing(Comparator.naturalOrder());
    }

    private static Map<AgeingBucket, BigDecimal> zeroBuckets() {
        Map<AgeingBucket, BigDecimal> buckets = new EnumMap<>(AgeingBucket.class);
        for (AgeingBucket b : AgeingBucket.values()) buckets.put(b, BigDecimal.ZERO);
        return buckets;
    }

    private static AgeingBucket classify(LocalDate dueDate, LocalDate asAt) {
        long daysOverdue = ChronoUnit.DAYS.between(dueDate, asAt);
        if (daysOverdue <= 0)  return AgeingBucket.CURRENT;
        if (daysOverdue <= 30) return AgeingBucket.D1_30;
        if (daysOverdue <= 60) return AgeingBucket.D31_60;
        if (daysOverdue <= 90) return AgeingBucket.D61_90;
        return AgeingBucket.D90_PLUS;
    }
}
