package com.erp.modules.hr.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.hr.domain.dto.CreatePayeBandSetRequest;
import com.erp.modules.hr.repository.PayeBandRepository;
import com.erp.modules.hr.repository.PayeBandSetRepository;
import com.erp.modules.hr.repository.StatutoryRateSetRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StatutoryRateServiceImpl} covering D5: reject a PAYE band-set
 * creation request with an empty bands list.
 */
class StatutoryRateServiceImplTest {

    private PayeBandSetRepository    payeBandSets;
    private PayeBandRepository       payeBands;
    private StatutoryRateSetRepository rateSets;
    private ScopeGuard               scopeGuard;
    private AuditService             audit;
    private StatutoryRateServiceImpl service;

    private static final Long COMPANY_ID = 10L;

    @BeforeEach
    void setUp() {
        payeBandSets = mock(PayeBandSetRepository.class);
        payeBands    = mock(PayeBandRepository.class);
        rateSets     = mock(StatutoryRateSetRepository.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);
        service = new StatutoryRateServiceImpl(payeBandSets, payeBands, rateSets, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(99L, "hr@erp.com", false, COMPANY_ID, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------
    // D5 — empty bands list is rejected at the service layer (400)
    // -----------------------------------------------------------------------

    @Test
    void createPayeBandSet_emptyBandsList_throwsIllegalArgument() {
        CreatePayeBandSetRequest req = new CreatePayeBandSetRequest(
                LocalDate.of(2025, 1, 1),
                BigDecimal.valueOf(270_000),
                "Test set",
                Collections.emptyList());

        assertThatThrownBy(() -> service.createPayeBandSet(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one band");

        verify(payeBandSets, never()).save(any());
        verify(payeBands, never()).save(any());
    }

    @Test
    void createPayeBandSet_nullBandsList_throwsIllegalArgument() {
        CreatePayeBandSetRequest req = new CreatePayeBandSetRequest(
                LocalDate.of(2025, 1, 1),
                BigDecimal.valueOf(270_000),
                "Test set",
                null);

        assertThatThrownBy(() -> service.createPayeBandSet(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one band");

        verify(payeBandSets, never()).save(any());
    }

    @Test
    void createPayeBandSet_withBands_proceedsToSave() {
        CreatePayeBandSetRequest.BandRequest band = new CreatePayeBandSetRequest.BandRequest(
                (short) 1, BigDecimal.ZERO, BigDecimal.valueOf(9), BigDecimal.ZERO);
        CreatePayeBandSetRequest req = new CreatePayeBandSetRequest(
                LocalDate.of(2025, 1, 1),
                BigDecimal.valueOf(270_000),
                "Normal set",
                List.of(band));

        // no duplicate
        when(payeBandSets.existsByCompanyIdAndEffectiveFrom(COMPANY_ID, LocalDate.of(2025, 1, 1)))
                .thenReturn(false);

        com.erp.modules.hr.domain.entity.PayeBandSet setEntity =
                new com.erp.modules.hr.domain.entity.PayeBandSet(
                        COMPANY_ID, LocalDate.of(2025, 1, 1),
                        BigDecimal.valueOf(270_000), "Normal set", 99L);
        when(payeBandSets.save(any())).thenReturn(setEntity);

        com.erp.modules.hr.domain.entity.PayeBand bandEntity =
                new com.erp.modules.hr.domain.entity.PayeBand(
                        null, COMPANY_ID, (short) 1,
                        BigDecimal.ZERO, BigDecimal.valueOf(9), BigDecimal.ZERO, 99L);
        when(payeBands.save(any())).thenReturn(bandEntity);

        service.createPayeBandSet(req); // must not throw

        verify(payeBandSets).save(any());
        verify(payeBands).save(any());
    }
}
