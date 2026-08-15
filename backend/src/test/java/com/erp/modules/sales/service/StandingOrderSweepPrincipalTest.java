package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.sales.domain.entity.StandingOrder;
import com.erp.modules.sales.repository.StandingOrderRepository;
import com.erp.platform.security.RequestContext;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The midnight standing-order sweep must be able to act (ADR-0062 P8-6).
 *
 * <h2>Why this class exists</h2>
 *
 * {@code generateDue()} is {@code @Scheduled}, so it runs on a pool thread where
 * {@link RequestContext} is empty. Every generated order goes through
 * {@code SalesOrderServiceImpl.create}, which calls {@code assertCanActIn(RequestContext.get(), ...)}
 * — handed a null principal, that denies, and the resulting {@code ForbiddenException} lands in the
 * sweep's own {@code catch}, is logged, and the loop moves on.
 *
 * <p>The consequence was silent and total: <b>standing orders had never generated anything, on any
 * installation</b>. No test named {@code generateDue} or {@code StandingOrderService} existed, which
 * is why it survived. The fix installs a system principal <b>per standing order</b>, scoped to that
 * order's own company and branch — not one global principal for the whole sweep.
 *
 * <p>These assertions are deliberately about the PRINCIPAL rather than about a generated sales
 * order: the principal is the thing that was wrong, and asserting on it does not require standing up
 * the whole sales-order creation path with its dozen collaborators.
 */
@DisplayName("the standing-order sweep runs with a scoped principal, not none")
class StandingOrderSweepPrincipalTest {

    private static final Long COMPANY = 42L;
    private static final Long BRANCH = 7L;

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    /** Captures the principal that was in scope when the sweep reached the generation call. */
    private RequestContext.Principal capturedDuringGeneration;

    private StandingOrderServiceImpl serviceThatCapturesPrincipal(StandingOrder due) {
        StandingOrderRepository standings = mock(StandingOrderRepository.class);
        when(standings.findDueForGeneration(any(LocalDate.class))).thenReturn(List.of(due));

        // The company lookup is the first thing generateSo does, so it is the earliest point at
        // which the sweep's principal is observable. Throwing afterwards keeps the rest of the
        // creation path out of this test — the sweep catches it, which is the behaviour under test
        // for the "cleared afterwards" assertion.
        var companies = mock(com.erp.modules.iam.repository.CompanyRepository.class);
        when(companies.findById(any())).thenAnswer(inv -> {
            capturedDuringGeneration = RequestContext.get();
            throw new IllegalStateException("stop here — the principal is what we are asserting on");
        });

        return new StandingOrderServiceImpl(
                standings,
                mock(com.erp.modules.sales.repository.StandingOrderLineRepository.class),
                mock(com.erp.modules.sales.repository.SalesOrderRepository.class),
                mock(SalesOrderService.class),
                mock(SalesDepthNumberGenerator.class),
                companies,
                mock(com.erp.modules.parties.repository.CustomerRepository.class),
                mock(com.erp.modules.products.repository.ProductRepository.class),
                mock(com.erp.modules.products.repository.UnitOfMeasureRepository.class),
                mock(com.erp.platform.events.OutboxPublisher.class),
                mock(com.erp.platform.security.ScopeGuard.class),
                mock(com.erp.platform.audit.AuditService.class));
    }

    private static StandingOrder dueOrder() {
        StandingOrder so = mock(StandingOrder.class);
        when(so.getCompanyId()).thenReturn(COMPANY);
        when(so.getBranchId()).thenReturn(BRANCH);
        when(so.getUid()).thenReturn("01STANDINGORDER0000000AAA");
        return so;
    }

    @Test
    @DisplayName("P8-6 · a principal is installed, scoped to the order's own company and branch")
    void sweepInstallsAScopedSystemPrincipal() {
        RequestContext.clear();   // the scheduler thread starts with nothing — that was the bug

        serviceThatCapturesPrincipal(dueOrder()).generateDue();

        assertThat(capturedDuringGeneration)
                .as("with no principal, assertCanActIn denies and the sweep silently generates "
                        + "NOTHING — which is what it did on every installation until now")
                .isNotNull();
        assertThat(capturedDuringGeneration.system())
                .as("SYSTEM (null userId) so it is exempt from the tenancy check: it replays a due "
                        + "order whose company is already fixed, it does not act for a tenant")
                .isTrue();
        assertThat(capturedDuringGeneration.root())
                .as("NOT root — canActIn must still require the company to match, so the sweep "
                        + "cannot wander outside the order it is generating")
                .isFalse();
        assertThat(capturedDuringGeneration.companyId()).isEqualTo(COMPANY);
        assertThat(capturedDuringGeneration.branchId()).isEqualTo(BRANCH);
    }

    @Test
    @DisplayName("P8-6 · the principal is cleared even when generation fails")
    void principalIsClearedAfterEachOrder() {
        RequestContext.clear();

        serviceThatCapturesPrincipal(dueOrder()).generateDue();

        // The scheduler thread is pooled and outlives the sweep. A principal left behind would be
        // inherited by whatever ran next on that thread — a system principal scoped to some
        // customer's company, silently in force for unrelated work.
        assertThat(RequestContext.get())
                .as("cleared in a finally, so a failed generation cannot leak a principal onto the "
                        + "pooled scheduler thread")
                .isNull();
    }
}
