package com.erp.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.fx.domain.dto.CompanyCurrencyDto;
import com.erp.modules.fx.service.CurrencyEnablementService;
import com.erp.platform.common.api.ApiResponseAdvice;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.JwtRequestContextFilter;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice test for the {@link CurrencyEnablementController} (ADR-0039 D-8).
 *
 * <p>Verifies the {@code GET /api/v1/fx/currencies/enabled} response shape —
 * the {@code ApiResponse<EnabledCurrenciesDto>} envelope with {@code resolvedDefault} and
 * {@code enabled} list — against mocked service.
 */
@WebMvcTest(controllers = CurrencyEnablementController.class,
        excludeAutoConfiguration = {SecurityAutoConfiguration.class,
                OAuth2ResourceServerAutoConfiguration.class},
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                classes = JwtRequestContextFilter.class))
@Import(ApiResponseAdvice.class)
class CurrencyEnablementControllerTest {

    @Autowired MockMvc mockMvc;

    @MockBean CurrencyEnablementService enablementService;
    @MockBean ScopeGuard                scopeGuard;

    @Test
    void getEnabled_returnsEnvelopedEnabledListAndResolvedDefault() throws Exception {
        // Arrange: uid resolution via service (no repository in controller)
        when(enablementService.resolveCompanyId("UID-CO-1")).thenReturn(1L);

        // Stub scope guard (no-op)
        doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());

        // Stub service queries
        when(enablementService.enabledForCompany(1L))
                .thenReturn(List.of(
                        new CompanyCurrencyDto(1L, "UID-1", null, "TZS", true,  true),
                        new CompanyCurrencyDto(2L, "UID-2", null, "USD", false, true),
                        new CompanyCurrencyDto(3L, "UID-3", null, "KES", false, true)));
        when(enablementService.resolveDefault(1L, null))
                .thenReturn(CurrencyCode.of("TZS"));

        // Act + Assert
        mockMvc.perform(get("/api/v1/fx/currencies/enabled")
                        .param("companyUid", "UID-CO-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resolvedDefault").value("TZS"))
                .andExpect(jsonPath("$.data.enabled").isArray())
                .andExpect(jsonPath("$.data.enabled.length()").value(3))
                .andExpect(jsonPath("$.data.enabled[0]").value("TZS"))
                .andExpect(jsonPath("$.data.enabled[1]").value("USD"))
                .andExpect(jsonPath("$.data.enabled[2]").value("KES"))
                .andExpect(jsonPath("$.errors").isEmpty());
    }
}
