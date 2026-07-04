package com.erp.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * ADR-0049 §2: provider selection via {@code erp.fiscal.provider} + the prod fail-fast guard.
 * Exercises the real {@code @ConditionalOnProperty} wiring + {@link FiscalisationConfig} through a
 * lightweight Spring context (no full app boot, no DB).
 */
class FiscalisationProviderSelectionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FiscalisationConfig.class,
                    NotConfiguredFiscalisationProvider.class,
                    SimulatedFiscalisationProvider.class);

    @Test
    void absentConfig_resolvesToNotConfiguredProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FiscalisationProvider.class);
            assertThat(context).hasSingleBean(NotConfiguredFiscalisationProvider.class);
            assertThat(context).doesNotHaveBean(SimulatedFiscalisationProvider.class);
        });
    }

    @Test
    void explicitNone_resolvesToNotConfiguredProvider() {
        contextRunner.withPropertyValues("erp.fiscal.provider=none").run(context -> {
            assertThat(context).hasSingleBean(NotConfiguredFiscalisationProvider.class);
            assertThat(context).doesNotHaveBean(SimulatedFiscalisationProvider.class);
        });
    }

    @Test
    void simulated_wiresSimulatedProviderOnly() {
        contextRunner.withPropertyValues("erp.fiscal.provider=simulated").run(context -> {
            assertThat(context).hasSingleBean(FiscalisationProvider.class);
            assertThat(context).hasSingleBean(SimulatedFiscalisationProvider.class);
            assertThat(context).doesNotHaveBean(NotConfiguredFiscalisationProvider.class);
        });
    }

    @Test
    void simulated_underProdProfile_failsFastAtStartup() {
        contextRunner
                .withPropertyValues("erp.fiscal.provider=simulated", "spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasFailed();
                    Throwable rootCause = rootCauseOf(context.getStartupFailure());
                    assertThat(rootCause).isInstanceOf(IllegalStateException.class);
                    assertThat(rootCause.getMessage()).contains("simulated").contains("prod");
                });
    }

    @Test
    void unknownProvider_failsFastWithAClearMessage() {
        // A typo'd/not-yet-wired value (e.g. 'tra' before its adapter ships) matches no
        // @ConditionalOnProperty — without the guard the context would fail with a cryptic
        // NoSuchBeanDefinitionException; FiscalisationConfig turns it into a clear message.
        contextRunner.withPropertyValues("erp.fiscal.provider=bogus").run(context -> {
            assertThat(context).hasFailed();
            Throwable rootCause = rootCauseOf(context.getStartupFailure());
            assertThat(rootCause).isInstanceOf(IllegalStateException.class);
            assertThat(rootCause.getMessage()).contains("Unknown erp.fiscal.provider");
        });
    }

    @Test
    void none_underProdProfile_startsCleanly() {
        contextRunner
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(NotConfiguredFiscalisationProvider.class);
                });
    }

    private static Throwable rootCauseOf(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
    }
}
