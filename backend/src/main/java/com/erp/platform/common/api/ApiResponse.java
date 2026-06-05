package com.erp.platform.common.api;

import java.util.List;

/**
 * The single response envelope for every REST endpoint (PROJECT-CONVENTIONS §3.1).
 *
 * <p>Controllers return the raw payload {@code T}; {@link ApiResponseAdvice} wraps it into this
 * envelope on the way out. Errors carry only user-safe strings — never internal exception text.
 *
 * @param data   the payload, or {@code null} on error
 * @param errors user-facing error messages; empty on success
 * @param meta   optional metadata (paging, etc.); {@code null} when not used
 */
public record ApiResponse<T>(T data, List<String> errors, Object meta) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, List.of(), null);
    }

    public static <T> ApiResponse<T> ok(T data, Object meta) {
        return new ApiResponse<>(data, List.of(), meta);
    }

    public static <T> ApiResponse<T> error(List<String> errors) {
        return new ApiResponse<>(null, List.copyOf(errors), null);
    }

    public static <T> ApiResponse<T> error(String error) {
        return new ApiResponse<>(null, List.of(error), null);
    }
}
