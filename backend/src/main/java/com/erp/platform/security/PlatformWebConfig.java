package com.erp.platform.security;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers {@link PlatformOrganisationWriteGuard} on the API (owner, 2026-11-07). */
@Configuration
public class PlatformWebConfig implements WebMvcConfigurer {

    private final PlatformOrganisationWriteGuard guard;

    public PlatformWebConfig(PlatformOrganisationWriteGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(guard).addPathPatterns("/api/**");
    }
}
