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
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes AP ageing on demand (ADR-0015 D-7, mirrors ArAgeingQuery).
 * Buckets: CURRENT, D1_30, D31_60, D61_90, D90_PLUS — same 5 as AR.
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

        Map<AgeingBucket, BigDecimal> buckets = new EnumMap<>(AgeingBucket.class);
        for (AgeingBucket b : AgeingBucket.values()) buckets.put(b, BigDecimal.ZERO);

        for (SupplierBill bill : openItems) {
            AgeingBucket bucket = classify(bill.getDueDate(), asAt);
            buckets.merge(bucket, bill.getOutstandingAmount(), BigDecimal::add);
        }

        List<ApAgeingRowDto> rows = new ArrayList<>();
        for (AgeingBucket b : AgeingBucket.values()) {
            rows.add(new ApAgeingRowDto(b, buckets.get(b), currency));
        }
        return rows;
    }

    // -------------------------------------------------------------------------

    private static AgeingBucket classify(LocalDate dueDate, LocalDate asAt) {
        long daysOverdue = ChronoUnit.DAYS.between(dueDate, asAt);
        if (daysOverdue <= 0)  return AgeingBucket.CURRENT;
        if (daysOverdue <= 30) return AgeingBucket.D1_30;
        if (daysOverdue <= 60) return AgeingBucket.D31_60;
        if (daysOverdue <= 90) return AgeingBucket.D61_90;
        return AgeingBucket.D90_PLUS;
    }
}
