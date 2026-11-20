package com.erp.modules.subscription.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.subscription.domain.enums.CapResource;
import com.erp.modules.subscription.domain.enums.PlanCode;
import com.erp.modules.subscription.domain.enums.PlanModule;
import com.erp.modules.subscription.domain.enums.SubscriptionStage;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Entitlement (ADR-0069)")
class EntitlementTest {

    private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");
    private static final LocalDate DUE = LocalDate.of(2026, 12, 1);

    private static Entitlement of(PlanCode plan, LocalDate trialEnds, LocalDate paidUntil,
                                  int grace, int readOnly) {
        return new Entitlement(plan, false, false, null, null, null, null, trialEnds, paidUntil,
                grace, readOnly, DAR);
    }

    @Nested
    @DisplayName("the non-payment ladder (D-6)")
    class Ladder {

        private final Entitlement paid = of(PlanCode.BASIC, null, DUE, 7, 7);

        @Test
        void paidUpToAndIncludingTheDueDateIsActive() {
            assertThat(paid.stageOn(DUE.minusDays(30))).isEqualTo(SubscriptionStage.ACTIVE);
            assertThat(paid.stageOn(DUE)).isEqualTo(SubscriptionStage.ACTIVE);
        }

        @Test
        void graceRunsForGraceDaysAfterTheDueDate() {
            assertThat(paid.stageOn(DUE.plusDays(1))).isEqualTo(SubscriptionStage.GRACE);
            assertThat(paid.stageOn(DUE.plusDays(7))).isEqualTo(SubscriptionStage.GRACE);
        }

        @Test
        void thenReadOnlyForReadOnlyDays() {
            assertThat(paid.readOnlyFrom()).isEqualTo(DUE.plusDays(8));
            assertThat(paid.stageOn(DUE.plusDays(8))).isEqualTo(SubscriptionStage.READ_ONLY);
            assertThat(paid.stageOn(DUE.plusDays(14))).isEqualTo(SubscriptionStage.READ_ONLY);
        }

        @Test
        void thenSuspended() {
            assertThat(paid.suspendedFrom()).isEqualTo(DUE.plusDays(15));
            assertThat(paid.stageOn(DUE.plusDays(15))).isEqualTo(SubscriptionStage.SUSPENDED);
            assertThat(paid.stageOn(DUE.plusYears(1))).isEqualTo(SubscriptionStage.SUSPENDED);
        }

        @Test
        void zeroGraceGoesStraightToReadOnly() {
            Entitlement strict = of(PlanCode.BASIC, null, DUE, 0, 3);
            assertThat(strict.stageOn(DUE.plusDays(1))).isEqualTo(SubscriptionStage.READ_ONLY);
            assertThat(strict.stageOn(DUE.plusDays(4))).isEqualTo(SubscriptionStage.SUSPENDED);
        }

        @Test
        void aTrialWithoutPaymentFollowsTheSameLadder() {
            Entitlement trial = of(PlanCode.STANDARD, DUE, null, 7, 7);
            assertThat(trial.stageOn(DUE)).isEqualTo(SubscriptionStage.TRIAL);
            assertThat(trial.stageOn(DUE.plusDays(1))).isEqualTo(SubscriptionStage.GRACE);
            assertThat(trial.stageOn(DUE.plusDays(8))).isEqualTo(SubscriptionStage.READ_ONLY);
        }

        @Test
        void aPaymentOverridesTheTrialEndDate() {
            Entitlement paidAfterTrial = of(PlanCode.STANDARD, DUE, DUE.plusMonths(1), 7, 7);
            assertThat(paidAfterTrial.dueDate()).isEqualTo(DUE.plusMonths(1));
            assertThat(paidAfterTrial.stageOn(DUE.plusDays(10))).isEqualTo(SubscriptionStage.ACTIVE);
        }

        @Test
        void noDatesMeansItNeverLapses() {
            Entitlement contract = of(PlanCode.ENTERPRISE, null, null, 7, 7);
            assertThat(contract.stageOn(LocalDate.of(2099, 1, 1))).isEqualTo(SubscriptionStage.ACTIVE);
            assertThat(contract.readOnlyFrom()).isNull();
            assertThat(contract.suspendedFrom()).isNull();
        }
    }

    @Nested
    @DisplayName("modules (D-1, D-2, D-4)")
    class Modules {

        @Test
        void basicSellsReceivesStockAndFiscalisesButHasNoReceivablesOrLedger() {
            Entitlement basic = of(PlanCode.BASIC, null, null, 7, 7);
            assertThat(basic.permits("POS.SALE.CREATE")).isTrue();
            assertThat(basic.permits("FISCAL.VIEW")).isTrue();
            assertThat(basic.permits("TAXRATE.VIEW")).isTrue();
            assertThat(basic.permits("PURCHASE.RECEIVE.DIRECT")).isTrue();
            assertThat(basic.permits("CURRENCY.VIEW")).isTrue();
            assertThat(basic.permits("REPORT.VIEW")).isTrue();
            assertThat(basic.permits("PURCHASE.ORDER.CREATE")).isFalse();
            assertThat(basic.permits("AR.VIEW")).isFalse();
            assertThat(basic.permits("STOCK.TRANSFER.CREATE")).isFalse();
            assertThat(basic.permits("GL.VIEW")).isFalse();
            assertThat(basic.permits("REPORT.PL.VIEW")).isFalse();
        }

        @Test
        void eachTierContainsTheOnesBelow() {
            Entitlement premium = of(PlanCode.PREMIUM, null, null, 7, 7);
            assertThat(premium.permits("AR.VIEW")).isTrue();
            assertThat(premium.permits("GL.POST")).isTrue();
            assertThat(premium.permits("BI.VIEW")).isTrue();
            assertThat(premium.permits("HR.PAYROLL.RUN")).isFalse();
            assertThat(premium.permits("BOM.VIEW")).isFalse();
            Entitlement enterprise = of(PlanCode.ENTERPRISE, null, null, 7, 7);
            assertThat(enterprise.permits("HR.PAYROLL.RUN")).isTrue();
            assertThat(enterprise.permits("PROJECTS.PROJECT.VIEW")).isTrue();
        }

        @Test
        void restaurantIsAnAddOnEvenOnEnterprise() {
            Entitlement enterprise = of(PlanCode.ENTERPRISE, null, null, 7, 7);
            assertThat(enterprise.permits("RESTAURANT.ORDER.MANAGE")).isFalse();
            Entitlement basicWithRestaurant = new Entitlement(PlanCode.BASIC, true, false, null,
                    null, null, null, null, null, 7, 7, DAR);
            assertThat(basicWithRestaurant.permits("RESTAURANT.ORDER.MANAGE")).isTrue();
            assertThat(PlanModule.of("RESTAURANT.KITCHEN.MANAGE")).isEqualTo(PlanModule.RESTAURANT);
        }
    }

    @Nested
    @DisplayName("caps (D-3)")
    class Caps {

        @Test
        void thePlanCapAppliesUnlessOverridden() {
            Entitlement basic = new Entitlement(PlanCode.BASIC, false, false, 8, null, null, null,
                    null, null, 7, 7, DAR);
            assertThat(basic.cap(CapResource.USERS)).isEqualTo(8);
            assertThat(basic.cap(CapResource.BRANCHES)).isEqualTo(1);
            assertThat(basic.cap(CapResource.TILLS)).isEqualTo(3);
            assertThat(basic.cap(CapResource.COMPANIES)).isEqualTo(1);
        }

        @Test
        void enterpriseIsUnlimitedUnlessAContractSetsACap() {
            Entitlement enterprise = new Entitlement(PlanCode.ENTERPRISE, false, false, null, 40,
                    null, null, null, null, 7, 7, DAR);
            assertThat(enterprise.cap(CapResource.USERS)).isNull();
            assertThat(enterprise.cap(CapResource.BRANCHES)).isEqualTo(40);
        }

        @Test
        void theOwnersNumbers() {
            assertThat(PlanCode.STANDARD.cap(CapResource.USERS)).isEqualTo(20);
            assertThat(PlanCode.STANDARD.cap(CapResource.BRANCHES)).isEqualTo(5);
            assertThat(PlanCode.STANDARD.cap(CapResource.TILLS)).isEqualTo(10);
            assertThat(PlanCode.STANDARD.cap(CapResource.COMPANIES)).isEqualTo(2);
            assertThat(PlanCode.PREMIUM.cap(CapResource.USERS)).isEqualTo(50);
            assertThat(PlanCode.PREMIUM.cap(CapResource.BRANCHES)).isEqualTo(15);
            assertThat(PlanCode.PREMIUM.cap(CapResource.TILLS)).isEqualTo(30);
            assertThat(PlanCode.PREMIUM.cap(CapResource.COMPANIES)).isEqualTo(3);
        }
    }
}
