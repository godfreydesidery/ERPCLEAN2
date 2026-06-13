package com.erp.platform.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 metadata and JWT bearer security scheme (ADR-0038 D-9).
 *
 * <p>Springdoc scans all {@code @RestController} beans and generates {@code /v3/api-docs} (JSON)
 * and {@code /swagger-ui/index.html} automatically. This bean adds the application title/version
 * and wires the JWT bearer scheme so the "Authorize" button in Swagger UI accepts a token and
 * sends {@code Authorization: Bearer <token>} on every try-it-out request.
 *
 * <p>Access is gated by {@code ERP_SWAGGER_ENABLED} (application.yml springdoc block).
 * Set {@code ERP_SWAGGER_ENABLED=false} in production to disable the docs surface.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI erpOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("ERP API")
                        .version("0.0.1-SNAPSHOT")
                        .description(
                                "ERPCLEAN2 modular-monolith backend. "
                                + "All endpoints (except auth + health) require a JWT bearer token. "
                                + "Obtain a token via POST /api/v1/auth/login, then click 'Authorize' "
                                + "and enter: Bearer <token>."))
                // Apply the bearer scheme globally so every endpoint shows the lock icon.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                                .name(BEARER_SCHEME)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description(
                                        "RS256 JWT issued by POST /api/v1/auth/login. "
                                        + "Access token TTL: 15 minutes. "
                                        + "Refresh via POST /api/v1/auth/refresh.")));
    }
}
