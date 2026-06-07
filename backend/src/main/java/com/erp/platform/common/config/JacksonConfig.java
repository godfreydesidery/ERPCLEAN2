package com.erp.platform.common.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Serialises {@code Long} as a JSON string globally (PROJECT-CONVENTIONS §3.3) so 64-bit ids
 * survive JavaScript's 53-bit number precision. The web client types every id field as
 * {@code string}; {@code uid} is already a string. Deserialisation still accepts both {@code 42}
 * and {@code "42"} (Jackson coerces numeric strings to Long on the way in).
 *
 * <p>Uses {@code modulesToInstall} (ADDITIVE) rather than {@code modules} (which REPLACES the whole
 * module set) so Spring Boot's auto-registered {@code JavaTimeModule} is preserved — otherwise
 * {@code java.time.Instant} (e.g. the outbox {@code SALE.FINALISED} payload's {@code finalisedAt})
 * fails to serialise.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("LongAsString");
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modulesToInstall(module);
        };
    }
}
