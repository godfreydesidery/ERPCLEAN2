package com.erp.api;

import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Slice 0 liveness endpoint. Returns the app's status; the response is auto-wrapped in
 * {@code ApiResponse<T>} by {@code ApiResponseAdvice}, so the web client receives
 * {@code { "data": { "status": "UP", ... }, "errors": [] }} and the interceptor unwraps it.
 *
 * <p>Proves the full request path (controller → advice → envelope) end-to-end before any feature.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "erp-api",
                "time", Instant.now().toString());
    }
}
