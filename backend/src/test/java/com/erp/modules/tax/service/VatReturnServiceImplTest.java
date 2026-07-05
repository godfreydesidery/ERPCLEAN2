package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnComputationDto;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.entity.VatReturn;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.repository.VatAdjustmentRepository;
import com.erp.modules.tax.repository.VatReturnBandRepository;
import com.erp.modules.tax.repository.VatReturnRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link VatReturnServiceImpl}. Full lifecycle acceptance bars (open / recompute /
 * carry-forward / GL settlement) are covered by the Testcontainers {@code VatReturnServiceIT}; this
 * class targets a business-rule branch that is cheap to pin without a database — persona UAT I4: a
 * VAT return must not be FILED before its period has actually ended.
 *
 * <p>R3: the guard reads through an injected {@link Clock} rather than a bare {@code
 * LocalDate.now()}, so these tests pin a fixed "today" ({@link #TODAY}) instead of depending on
 * wall-clock time — the service is constructed manually (not via {@code @InjectMocks}, which has
 * no mock to satisfy the {@code Clock} constructor parameter).
 */
@ExtendWith(MockitoExtension.class)
class VatReturnServiceImplTest {

    @Mock VatReturnRepository returns;
    @Mock VatReturnBandRepository bands;
    @Mock VatAdjustmentRepository adjustments;
    @Mock CompanyRepository companies;
    @Mock VatReturnNumberGenerator numberGen;
    @Mock VatReturnComputationReader computationReader;
    @Mock VatReturnFilingPoster filingPoster;
    @Mock ScopeGuard scopeGuard;
    @Mock AuditService audit;

    private VatReturnServiceImpl service;

    private static final Long COMPANY_ID = 1L;

    /** Fixed "today" for the period-end guard — 2026-06-15, arbitrary but deterministic. */
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 15);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new VatReturnServiceImpl(returns, bands, adjustments, companies, numberGen,
                computationReader, filingPoster, scopeGuard, audit, FIXED_CLOCK);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void file_periodNotYetEnded_rejectedWithFriendlyConflict() {
        RequestContext.set(new RequestContext.Principal(1L, "user", false, COMPANY_ID, 10L, null));
        VatReturn vatReturn = vatReturn(900L, "VATRUID000000000000000001",
                TODAY.minusDays(5), TODAY.plusDays(5), VatReturnStatus.DRAFT);
        when(returns.findByUid("VATRUID000000000000000001")).thenReturn(Optional.of(vatReturn));

        assertThatThrownBy(() -> service.file("VATRUID000000000000000001",
                new FileVatReturnRequest("TRA-001", TODAY)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("has not yet ended");

        verify(computationReader, never()).compute(any(), any(), any());
        verify(filingPoster, never()).post(any(), any());
        verify(returns, never()).save(any());
    }

    @Test
    void file_periodEndsToday_notBlockedByThePeriodGuard() {
        RequestContext.set(new RequestContext.Principal(1L, "user", false, COMPANY_ID, 10L, null));
        VatReturn vatReturn = vatReturn(901L, "VATRUID000000000000000002",
                TODAY.minusDays(10), TODAY, VatReturnStatus.DRAFT);
        when(returns.findByUid("VATRUID000000000000000002")).thenReturn(Optional.of(vatReturn));
        when(returns.findLatestBefore(COMPANY_ID, vatReturn.getPeriodYear(), vatReturn.getPeriodMonth()))
                .thenReturn(Optional.empty());
        when(computationReader.compute(COMPANY_ID, vatReturn.getPeriodStart(), vatReturn.getPeriodEnd()))
                .thenReturn(new VatReturnComputationDto(Map.of(), BigDecimal.ZERO, BigDecimal.ZERO));
        when(adjustments.findByVatReturnId(901L)).thenReturn(List.of());
        when(returns.save(any(VatReturn.class))).thenAnswer(inv -> inv.getArgument(0));
        when(filingPoster.post(any(VatReturn.class), any())).thenReturn(null);

        VatReturnDto dto = service.file("VATRUID000000000000000002",
                new FileVatReturnRequest("TRA-002", TODAY));

        assertThat(dto.status()).isEqualTo(VatReturnStatus.FILED);
        verify(filingPoster).post(any(VatReturn.class), any());
    }

    @Test
    void file_alreadyFiled_stillRejected_beforeThePeriodGuardEvenRuns() {
        RequestContext.set(new RequestContext.Principal(1L, "user", false, COMPANY_ID, 10L, null));
        VatReturn vatReturn = vatReturn(902L, "VATRUID000000000000000003",
                TODAY.minusMonths(2), TODAY.minusMonths(1).minusDays(1),
                VatReturnStatus.FILED);
        when(returns.findByUid("VATRUID000000000000000003")).thenReturn(Optional.of(vatReturn));

        assertThatThrownBy(() -> service.file("VATRUID000000000000000003",
                new FileVatReturnRequest("TRA-003", TODAY)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already been filed");
    }

    private static VatReturn vatReturn(Long id, String uid, LocalDate periodStart, LocalDate periodEnd,
                                        VatReturnStatus status) {
        VatReturn r = new VatReturn(COMPANY_ID, "VATR-" + id,
                (short) periodStart.getYear(), (short) periodStart.getMonthValue(),
                periodStart, periodEnd, periodEnd.plusDays(20), null, BigDecimal.ZERO, 1L);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "uid", uid);
        r.setStatus(status);
        return r;
    }
}
