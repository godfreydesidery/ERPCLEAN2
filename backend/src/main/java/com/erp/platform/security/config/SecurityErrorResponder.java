package com.erp.platform.security.config;

import com.erp.platform.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Writes the {@link ApiResponse} envelope for security-filter failures so 401/403 keep the same
 * contract as the rest of the API (those are thrown by Spring Security BEFORE the
 * {@code @RestControllerAdvice}, which only covers {@code com.erp.api}, so they must be handled here
 * — security review finding). Messages are generic: never name the missing permission or token
 * reason (no enumeration).
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** No / invalid bearer token → 401. */
    @Override
    public void commence(jakarta.servlet.http.HttpServletRequest request,
                         HttpServletResponse response,
                         org.springframework.security.core.AuthenticationException authException)
            throws IOException {
        write(response, HttpStatus.UNAUTHORIZED, "Authentication is required.");
    }

    /** Authenticated but lacks permission / out of scope → 403 (method-security denial path). */
    @Override
    public void handle(jakarta.servlet.http.HttpServletRequest request,
                       HttpServletResponse response,
                       org.springframework.security.access.AccessDeniedException ex)
            throws IOException {
        write(response, HttpStatus.FORBIDDEN, "You do not have permission to perform this action.");
    }

    private void write(HttpServletResponse response, HttpStatus status, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(message)));
    }
}
