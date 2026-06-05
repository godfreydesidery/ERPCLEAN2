package com.erp.platform.security.config;

import com.erp.platform.security.JwtRequestContextFilter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Security wiring (ARCHITECTURE §4–5). Stateless JWT resource server: auth + health are public,
 * everything else needs a valid bearer token. RBAC permission gates turn on in Slice 3; for now an
 * authenticated user can reach the IAM admin endpoints.
 *
 * <p>The {@link RequestContextFilter} runs after authentication to expose the principal's
 * user/company/branch to the service layer.
 */
@Configuration
@EnableConfigurationProperties({JwtProperties.class, SecurityProperties.class})
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtRequestContextFilter requestContextFilter)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // stateless API, token-based — no CSRF cookie
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/api/v1/auth/**", "/api/v1/health", "/actuator/**")
                        .permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> {
                }))
                .addFilterAfter(requestContextFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** Bcrypt at cost 12 (FR-IAM-08). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
