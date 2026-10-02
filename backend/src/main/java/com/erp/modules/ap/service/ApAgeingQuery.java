package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApAgeingRowDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes AP ageing on demand (ADR-0015 D-7, mirrors ArAgeingQuery).
 * Buckets: CURRENT, D1_30, D31_60, D61_90, D90_PLUS — same 5 as AR.
 *
 * <p>Amounts are in each bill's OWN currency, one five-bucket block per currency (as AR): a USD
 * bill is never added to TZS ones under the TZS label. Not converted to base, for the AR reason:
 * V62 back-filled pre-existing bills' base figures at rate 1 whatever their currency.
 */
@Component
@Transactional(readOnly = true)
public class ApAgeingQuery {

    private final SupplierBillRepository bills;
    private final CompanyRepository      companies;
    private final ScopeGuard             scopeGuard;

    public ApAgeingQuery(SupplierBillRepository bills,
                          CompanyRepository companies,
                          ScopeGuard scopeGuard) {
        this.bills      = bills;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    /** Ageing breakdown for a supplier as at a given date. */
    public List<ApAgeingRowDto> ageing(Long companyId, Long supplierId, LocalDate asAt) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS");

        List<SupplierBill> openItems = bills.findOpenForStatement(companyId, supplierId);

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

        List<ApAgeingRowDto> rows = new ArrayList<>();
        for (Map.Entry<String, Map<AgeingBucket, BigDecimal>> e : byCurrency.entrySet()) {
            for (AgeingBucket bk : AgeingBucket.values()) {
                rows.add(new ApAgeingRowDto(bk, e.getValue().get(bk), e.getKey()));
            }
        }
        return rows;
    }

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
