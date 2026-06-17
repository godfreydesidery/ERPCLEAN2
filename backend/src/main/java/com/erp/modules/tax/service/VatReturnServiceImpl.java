package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnBandDto;
import com.erp.modules.tax.domain.dto.VatReturnComputationDto;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.entity.VatReturn;
import com.erp.modules.tax.domain.entity.VatReturnBand;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.repository.VatAdjustmentRepository;
import com.erp.modules.tax.repository.VatReturnBandRepository;
import com.erp.modules.tax.repository.VatReturnRepository;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VAT return lifecycle service (ADR-0017 D-4, FR-VAT-01/02/08).
 */
@Service
@Transactional
public class VatReturnServiceImpl implements VatReturnService {

    private final VatReturnRepository          returns;
    private final VatReturnBandRepository      bands;
    private final VatAdjustmentRepository      adjustments;
    private final CompanyRepository            companies;
    private final VatReturnNumberGenerator     numberGen;
    private final VatReturnComputationReader   computationReader;
    private final VatReturnFilingPoster        filingPoster;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;

    public VatReturnServiceImpl(VatReturnRepository returns,
                                 VatReturnBandRepository bands,
                                 VatAdjustmentRepository adjustments,
                                 CompanyRepository companies,
                                 VatReturnNumberGenerator numberGen,
                                 VatReturnComputationReader computationReader,
                                 VatReturnFilingPoster filingPoster,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.returns          = returns;
        this.bands            = bands;
        this.adjustments      = adjustments;
        this.companies        = companies;
        this.numberGen        = numberGen;
        this.computationReader = computationReader;
        this.filingPoster     = filingPoster;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
    }

    // -------------------------------------------------------------------------
    // open
    // -------------------------------------------------------------------------

    @Override
    public VatReturnDto open(OpenVatReturnRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        short year  = req.periodYear().shortValue();
        short month = req.periodMonth().shortValue();

        if (returns.existsByCompanyIdAndPeriodYearAndPeriodMonth(companyId, year, month)) {
            throw new ConflictException(
                    "A VAT return already exists for company " + req.companyUid()
                    + " period " + year + "-" + String.format("%02d", month) + " (BR-VAT-01).");
        }

        YearMonth ym        = YearMonth.of(year, month);
        LocalDate periodStart = ym.atDay(1);
        LocalDate periodEnd   = ym.atEndOfMonth();
        LocalDate dueDate     = ym.plusMonths(1).atDay(20); // 20th of next month

        // Carry-forward: find the most recent FILED return before this period
        BigDecimal openingCredit = BigDecimal.ZERO;
        Long       priorReturnId = null;
        var priorOpt = returns.findLatestFiledBefore(companyId, year, month);
        if (priorOpt.isPresent()) {
            openingCredit = priorOpt.get().getClosingCredit();
            priorReturnId = priorOpt.get().getId();
        }

        String returnNumber = numberGen.next(companyId);
        VatReturn vatReturn = new VatReturn(companyId, returnNumber, year, month,
                periodStart, periodEnd, dueDate, priorReturnId, openingCredit, actorId());
        vatReturn = returns.save(vatReturn);

        // First compute
        vatReturn = compute(vatReturn, companyId, periodStart, periodEnd);

        audit.record(AuditEvent.of(AuditActions.VAT_RETURN_PREPARE, "vat_returns",
                vatReturn.getId(), vatReturn.getUid())
                .detail(Map.of("returnNumber", returnNumber,
                        "period", year + "-" + String.format("%02d", month))));

        return toDto(vatReturn);
    }

    // -------------------------------------------------------------------------
    // recompute
    // -------------------------------------------------------------------------

    @Override
    public VatReturnDto recompute(String uid) {
        VatReturn vatReturn = Lookups.orNotFound(returns.findByUid(uid), "VatReturn", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), vatReturn.getCompanyId());

        if (vatReturn.getStatus() == VatReturnStatus.FILED) {
            throw new IllegalStateException(
                    "Cannot recompute a FILED VAT return (BR-VAT-02): " + uid);
        }

        vatReturn = compute(vatReturn, vatReturn.getCompanyId(),
                vatReturn.getPeriodStart(), vatReturn.getPeriodEnd());

        audit.record(AuditEvent.of(AuditActions.VAT_RETURN_PREPARE, "vat_returns",
                vatReturn.getId(), vatReturn.getUid())
                .detail(Map.of("action", "recompute", "returnNumber", vatReturn.getReturnNumber())));

        return toDto(vatReturn);
    }

    // -------------------------------------------------------------------------
    // file
    // -------------------------------------------------------------------------

    @Override
    public VatReturnDto file(String uid, FileVatReturnRequest req) {
        VatReturn vatReturn = Lookups.orNotFound(returns.findByUid(uid), "VatReturn", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), vatReturn.getCompanyId());

        if (vatReturn.getStatus() == VatReturnStatus.FILED) {
            throw new IllegalStateException(
                    "VAT return already FILED (BR-VAT-11): " + uid);
        }

        // Prior period must be FILED first (D-4 recommended default)
        var priorAny = returns.findLatestBefore(vatReturn.getCompanyId(),
                vatReturn.getPeriodYear(), vatReturn.getPeriodMonth());
        if (priorAny.isPresent() && priorAny.get().getStatus() != VatReturnStatus.FILED) {
            throw new IllegalStateException(
                    "Prior period return " + priorAny.get().getReturnNumber()
                    + " must be FILED before this period can be filed (D-4).");
        }

        // Step 1: final recompute (freeze figures)
        vatReturn = compute(vatReturn, vatReturn.getCompanyId(),
                vatReturn.getPeriodStart(), vatReturn.getPeriodEnd());

        // Step 2: record filing metadata
        vatReturn.setFilingReference(req.filingReference());
        vatReturn.setFilingDate(req.filingDate());
        vatReturn.setFiledAt(Instant.now());
        vatReturn.setFiledBy(actorId());

        // Step 3: post synchronous GL settlement (D-8); GL failure rolls back the whole TX
        String journalUid = filingPoster.post(vatReturn, actorId());
        vatReturn.setPostedJournalUid(journalUid);

        // Step 4: lock the return
        vatReturn.setStatus(VatReturnStatus.FILED);
        vatReturn.setUpdatedAt(Instant.now());
        vatReturn.setUpdatedBy(actorId());
        vatReturn = returns.save(vatReturn);

        audit.record(AuditEvent.of(AuditActions.VAT_RETURN_FILE, "vat_returns",
                vatReturn.getId(), vatReturn.getUid())
                .detail(Map.of("returnNumber", vatReturn.getReturnNumber(),
                        "filingReference", req.filingReference(),
                        "glEntryUid", journalUid != null ? journalUid : "(nil return — no journal)",
                        "netVat", vatReturn.getNetVat().toPlainString())));

        return toDto(vatReturn);
    }

    // -------------------------------------------------------------------------
    // reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public VatReturnDto getByUid(String uid) {
        VatReturn vatReturn = Lookups.orNotFound(returns.findByUid(uid), "VatReturn", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), vatReturn.getCompanyId());
        return toDto(vatReturn);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<VatReturnDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return returns.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    // -------------------------------------------------------------------------
    // Internal compute helper
    // -------------------------------------------------------------------------

    /** Rebuild band rows, refresh totals, update net/closing_credit. Returns saved entity. */
    private VatReturn compute(VatReturn vatReturn, Long companyId,
                               LocalDate start, LocalDate end) {
        VatReturnComputationDto comp = computationReader.compute(companyId, start, end);

        // Rebuild band rows (delete + reinsert)
        bands.deleteByVatReturnId(vatReturn.getId());
        bands.flush();

        Long actorId = actorId();
        for (Map.Entry<String, VatReturnComputationDto.BandTotalsDto> entry
                : comp.byBand().entrySet()) {
            VatReturnComputationDto.BandTotalsDto b = entry.getValue();
            bands.save(new VatReturnBand(vatReturn.getId(), companyId,
                    entry.getKey(), b.taxableBase(), b.outputVat(), actorId));
        }

        // Refresh totals
        vatReturn.setOutputVat(comp.totalOutput());
        vatReturn.setInputVat(comp.totalInput());

        // Re-sum adjustments (signed)
        BigDecimal adjTotal = adjustments.findByVatReturnId(vatReturn.getId()).stream()
                .map(a -> a.signedAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        vatReturn.setAdjustmentsTotal(adjTotal);

        // net = output − input + adjustments − opening_credit
        BigDecimal net = comp.totalOutput()
                .subtract(comp.totalInput())
                .add(adjTotal)
                .subtract(vatReturn.getOpeningCredit());
        vatReturn.setNetVat(net);

        // closing_credit = max(−net, 0)
        BigDecimal closingCredit = net.negate().max(BigDecimal.ZERO);
        vatReturn.setClosingCredit(closingCredit);

        vatReturn.setUpdatedAt(Instant.now());
        vatReturn.setUpdatedBy(actorId);
        return returns.save(vatReturn);
    }

    // -------------------------------------------------------------------------

    private VatReturnDto toDto(VatReturn r) {
        List<VatReturnBandDto> bandDtos = bands.findByVatReturnId(r.getId()).stream()
                .map(b -> new VatReturnBandDto(b.getId(), b.getVatReturnId(),
                        b.getTaxBand(), b.getTaxableBase(), b.getOutputVat()))
                .toList();
        return new VatReturnDto(
                r.getId(), r.getUid(), r.getCompanyId(),
                r.getReturnNumber(), r.getPeriodYear(), r.getPeriodMonth(),
                r.getPeriodStart(), r.getPeriodEnd(), r.getDueDate(),
                r.getStatus(), r.getOutputVat(), r.getInputVat(),
                r.getAdjustmentsTotal(), r.getOpeningCredit(),
                r.getNetVat(), r.getClosingCredit(),
                r.getPriorReturnId(), r.getFilingReference(), r.getFilingDate(),
                r.getPostedJournalUid(), r.getFiledAt(), r.getFiledBy(),
                // P2-M1: payment tracking + turnover figures
                r.getPaidAt(), r.getPaidAmount(), r.getPaymentReference(),
                r.getSalesTurnover(), r.getPurchasesTurnover(),
                r.getZeroRatedSales(), r.getExemptSales(),
                bandDtos);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
