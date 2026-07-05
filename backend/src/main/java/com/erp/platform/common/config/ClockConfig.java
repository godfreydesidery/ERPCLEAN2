package com.erp.platform.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's single {@link Clock} bean. Lets a service depend on {@code Clock} instead of
 * calling {@code LocalDate.now()} / {@code Instant.now()} directly, so a test can inject a fixed
 * clock instead of relying on wall-clock time (persona UAT I4 follow-up R3: {@code
 * VatReturnServiceImpl}'s period-end filing guard is the first consumer).
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock systemUtc() {
        return Clock.systemUTC();
    }
}
