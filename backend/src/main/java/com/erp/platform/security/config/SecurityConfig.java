package com.erp.platform.security.config;

import com.erp.platform.security.ErpPermissionEvaluator;
import com.erp.platform.security.JwtRequestContextFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security wiring (ARCHITECTURE §4–5, ADR-0002). Stateless JWT resource server: auth + health are
 * public, everything else needs a valid bearer token. From Slice 3, method security is ON:
 * {@code @PreAuthorize("hasPermission(...)")} on the IAM controllers is enforced by the custom
 * {@link ErpPermissionEvaluator} (permission + tenant scope). This CLOSES the dev-open window (R1).
 *
 * <p>The {@link JwtRequestContextFilter} runs after authentication to expose the principal's
 * user/company/branch to the resolver and service layer. 401/403 from the security filters are
 * rendered as the {@code ApiResponse} envelope by {@link SecurityErrorResponder}.
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
                .addFilterAfter(requestContextFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Expose the custom {@link ErpPermissionEvaluator} to {@code hasPermission(...)} in SpEL. */
    @Bean
    public MethodSecurityExpressionHandler methodSecurityExpressionHandler(
            ErpPermissionEvaluator permissionEvaluator) {
        DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
        handler.setPermissionEvaluator(permissionEvaluator);
        return handler;
    }

    /** Bcrypt at cost 12 (FR-IAM-08). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
