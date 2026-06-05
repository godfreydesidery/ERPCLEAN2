package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.platform.common.api.ApiResponseAdvice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the health endpoint AND the {@code ApiResponseAdvice} envelope. {@code
 * @WebMvcTest} loads only the web layer (this one controller + the advice) — no JPA, no DataSource,
 * no Flyway, and security auto-config is excluded (the envelope path is what's under test, not auth;
 * health is permitAll anyway). Keeps the test fast and independent of the security spine.
 */
@WebMvcTest(controllers = HealthController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class})
@Import(ApiResponseAdvice.class)
class HealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void health_returnsUp_wrappedInEnvelope() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.service").value("erp-api"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
