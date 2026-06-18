package com.erp.modules.iam.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.junit.jupiter.api.Test;

/**
 * Pins the wire shape of {@link BranchDto}: numeric ids serialise as JSON STRINGS (the global
 * Long-as-string contract from JacksonConfig) so 64-bit ids survive JavaScript. Fast — no Spring
 * context; configures a mapper that mirrors the app's customizer.
 */
class BranchDtoJsonTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new SimpleModule()
                    .addSerializer(Long.class, ToStringSerializer.instance));

    @Test
    void ids_serialiseAsStrings() throws Exception {
        BranchDto dto = new BranchDto(
                42L, "01HZX0BRANCHUID0000000000", 7L, "01HZX0COMPANYUID000000000",
                "BR-01", "Kariakoo", "Africa/Dar_es_Salaam", true, null, null, "ACTIVE");

        String json = mapper.writeValueAsString(dto);

        // id and companyId are quoted (strings), uid is a string, isDefault is a real boolean.
        assertThat(json).contains("\"id\":\"42\"");
        assertThat(json).contains("\"companyId\":\"7\"");
        assertThat(json).contains("\"uid\":\"01HZX0BRANCHUID0000000000\"");
        assertThat(json).contains("\"isDefault\":true");
    }
}
