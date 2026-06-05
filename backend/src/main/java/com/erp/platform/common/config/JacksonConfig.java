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
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longAsStringCustomizer() {
        return builder -> {
            SimpleModule module = new SimpleModule("LongAsString");
            module.addSerializer(Long.class, ToStringSerializer.instance);
            module.addSerializer(Long.TYPE, ToStringSerializer.instance);
            builder.modules(module);
        };
    }
}
