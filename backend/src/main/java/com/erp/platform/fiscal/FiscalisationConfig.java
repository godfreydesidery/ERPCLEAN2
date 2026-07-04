package com.erp.platform.fiscal;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Wires {@link FiscalisationProperties} and enforces the prod guard (ADR-0049 §2): the active
 * provider is selected by {@code @ConditionalOnProperty} on {@link NotConfiguredFiscalisationProvider}
 * / {@link SimulatedFiscalisationProvider} (and, in future, the real TRA adapter) — this class does
 * not itself construct a provider bean.
 *
 * <p>Fail-closed, mirroring {@code BootstrapRunner}'s admin-password guard: a {@code prod}-profile
 * boot with {@code erp.fiscal.provider=simulated} refuses to start. The simulated provider fabricates
 * a fake fiscal number and must never masquerade as production fiscalisation.
 */
@Configuration
@EnableConfigurationProperties(FiscalisationProperties.class)
public class FiscalisationConfig {

    private static final Logger log = LoggerFactory.getLogger(FiscalisationConfig.class);

    /** Providers wired today. A future TRA adapter adds its key here when it ships. */
    private static final Set<String> SUPPORTED_PROVIDERS = Set.of("none", "simulated");

    public FiscalisationConfig(FiscalisationProperties props, Environment env) {
        String provider = props.effectiveProvider();
        // Fail fast on a typo'd/unknown value: it would match no @ConditionalOnProperty, leaving zero
        // FiscalisationProvider beans and a cryptic NoSuchBeanDefinitionException at startup instead.
        if (!SUPPORTED_PROVIDERS.contains(provider)) {
            throw new IllegalStateException(
                    "Unknown erp.fiscal.provider='" + provider + "'. Supported values: "
                            + SUPPORTED_PROVIDERS + " (default 'none'). A real fiscal-device adapter "
                            + "is not yet wired.");
        }
        if ("simulated".equals(provider) && env.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "erp.fiscal.provider=simulated is not permitted under the 'prod' profile. "
                            + "The simulated provider fabricates a fake fiscal number and must never run "
                            + "in production. Set erp.fiscal.provider=none (the default) until a real "
                            + "fiscal-device adapter is configured.");
        }
        log.info("Fiscalisation provider configured: {}", provider);
    }
}
