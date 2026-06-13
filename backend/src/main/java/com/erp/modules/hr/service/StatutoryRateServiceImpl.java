package com.erp.modules.hr.service;

import com.erp.modules.hr.domain.dto.CreatePayeBandSetRequest;
import com.erp.modules.hr.domain.dto.CreateStatutoryRateSetRequest;
import com.erp.modules.hr.domain.dto.PayeBandSetDto;
import com.erp.modules.hr.domain.dto.StatutoryRateSetDto;
import com.erp.modules.hr.domain.entity.PayeBand;
import com.erp.modules.hr.domain.entity.PayeBandSet;
import com.erp.modules.hr.domain.entity.StatutoryRateSet;
import com.erp.modules.hr.repository.PayeBandRepository;
import com.erp.modules.hr.repository.PayeBandSetRepository;
import com.erp.modules.hr.repository.StatutoryRateSetRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class StatutoryRateServiceImpl implements StatutoryRateService {

    private final PayeBandSetRepository    payeBandSets;
    private final PayeBandRepository       payeBands;
    private final StatutoryRateSetRepository rateSets;
    private final ScopeGuard               scopeGuard;
    private final AuditService             audit;

    public StatutoryRateServiceImpl(PayeBandSetRepository payeBandSets,
                                     PayeBandRepository payeBands,
                                     StatutoryRateSetRepository rateSets,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.payeBandSets = payeBandSets;
        this.payeBands    = payeBands;
        this.rateSets     = rateSets;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
    }

    @Override
    public PayeBandSetDto createPayeBandSet(CreatePayeBandSetRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);

        PayeBandSet set = payeBandSets.save(new PayeBandSet(companyId, req.effectiveFrom(),
                req.taxFreeThreshold(), req.description(), p.userId()));

        List<PayeBand> savedBands = req.bands().stream()
                .map(b -> payeBands.save(new PayeBand(set.getId(), companyId, b.bandNo(),
                        b.lowerBound(), b.marginalRate(), b.cumulativeFixedTax(), p.userId())))
                .toList();

        audit.record(AuditEvent.of(AuditActions.HR_STATUTORY_PAYE_CREATE, "paye_band_sets", set.getId(), null));
        return toBandSetDto(set, savedBands);
    }

    @Override
    @Transactional(readOnly = true)
    public PayeBandSetDto getPayeBandSetByUid(String uid) {
        PayeBandSet set = payeBandSets.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("PayeBandSet", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), set.getCompanyId());
        List<PayeBand> bands = payeBands.findByPayeBandSetIdOrderByBandNoAsc(set.getId());
        return toBandSetDto(set, bands);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PayeBandSetDto> listPayeBandSets(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return payeBandSets.findByCompanyIdOrderByEffectiveFromDesc(companyId).stream()
                .map(set -> toBandSetDto(set, payeBands.findByPayeBandSetIdOrderByBandNoAsc(set.getId())))
                .toList();
    }

    @Override
    public StatutoryRateSetDto createRateSet(CreateStatutoryRateSetRequest req) {
        RequestContext.Principal p = RequestContext.get();
        Long companyId = p.companyId();
        scopeGuard.assertCanActIn(p, companyId);

        StatutoryRateSet srs = rateSets.save(new StatutoryRateSet(companyId, req.rateType(),
                req.effectiveFrom(), req.employeeRate(), req.employerRate(), req.basis(),
                req.ceilingAmount(), req.headcountThreshold(), req.description(), p.userId()));
        audit.record(AuditEvent.of(AuditActions.HR_STATUTORY_RATE_CREATE, "statutory_rate_sets", srs.getId(), null));
        return toRateSetDto(srs);
    }

    @Override
    @Transactional(readOnly = true)
    public StatutoryRateSetDto getRateSetByUid(String uid) {
        StatutoryRateSet srs = rateSets.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StatutoryRateSet", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), srs.getCompanyId());
        return toRateSetDto(srs);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StatutoryRateSetDto> listRateSets(Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return rateSets.findByCompanyIdOrderByRateTypeAscEffectiveFromDesc(companyId)
                .stream().map(this::toRateSetDto).toList();
    }

    // --- mappers ---

    private PayeBandSetDto toBandSetDto(PayeBandSet set, List<PayeBand> bands) {
        List<PayeBandSetDto.PayeBandDto> bandDtos = bands.stream()
                .map(b -> new PayeBandSetDto.PayeBandDto(b.getId(), b.getUid(),
                        b.getBandNo(), b.getLowerBound(),
                        b.getMarginalRate(), b.getCumulativeFixedTax()))
                .toList();
        return new PayeBandSetDto(set.getId(), set.getUid(), set.getCompanyId(),
                set.getEffectiveFrom(), set.getTaxFreeThreshold(), set.getDescription(), bandDtos);
    }

    private StatutoryRateSetDto toRateSetDto(StatutoryRateSet srs) {
        return new StatutoryRateSetDto(srs.getId(), srs.getUid(), srs.getCompanyId(),
                srs.getRateType(), srs.getEffectiveFrom(),
                srs.getEmployeeRate(), srs.getEmployerRate(), srs.getBasis(),
                srs.getCeilingAmount(), srs.getHeadcountThreshold(),
                srs.isActive(), srs.getDescription());
    }
}
