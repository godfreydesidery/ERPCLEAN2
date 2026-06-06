package com.erp.platform.security.config;

import com.erp.platform.security.JwtRequestContextFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security wiring (ARCHITECTURE §4–5, ADR-0002). Stateless JWT resource server: auth + health are
 * public, everything else needs a valid bearer token. Method security is ON: controllers are gated
 * via {@code @PreAuthorize("@perm.has(...)")} / {@code @perm.scoped(...)} (ADR-0002 Bug-1 fix —
 * Spring's SpEL {@code hasPermission} has no 1-arg form; the {@code @perm} bean reference needs no
 * custom expression handler). This CLOSES the dev-open window (R1).
 *
 * <p>{@link JwtRequestContextFilter} is placed AFTER {@link BearerTokenAuthenticationFilter} so the
 * JWT is already validated and the principal is in the SecurityContext when RequestContext is
 * populated (Bug-2 fix — placing it before the bearer filter left the context empty).
 * 401/403 from the security filters are rendered as the {@code ApiResponse} envelope by
 * {@link SecurityErrorResponder}.
 */
@Configuration
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtRequestContextFilter requestContextFilter,
                                           SecurityErrorResponder errorResponder)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless API, token-based — no CSRF cookie
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh",
                                "/api/v1/auth/logout", "/api/v1/health", "/actuator/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
                }))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorResponder)
                        .accessDeniedHandler(errorResponder))
                // Bug-2 fix: must run AFTER BearerTokenAuthenticationFilter so the JWT is already
                // authenticated and SecurityContextHolder.getContext().getAuthentication() is set.
                .addFilterAfter(requestContextFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Bcrypt at cost 12 (FR-IAM-08). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
