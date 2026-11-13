package com.erp.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the API interceptors: {@link PlatformOrganisationWriteGuard} (owner, 2026-11-07) and
 * {@link SubscriptionAccessGuard} (ADR-0069).
 */
@Configuration
public class PlatformWebConfig implements WebMvcConfigurer {

    private final PlatformOrganisationWriteGuard guard;
    private final SubscriptionAccessGuard subscriptionGuard;

    public PlatformWebConfig(PlatformOrganisationWriteGuard guard,
                             SubscriptionAccessGuard subscriptionGuard) {
        this.guard = guard;
        this.subscriptionGuard = subscriptionGuard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/**");
        registry.addInterceptor(subscriptionGuard).addPathPatterns("/api/**");
    }
}
