package com.erp.platform.common.api;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Translates exceptions into the {@link ApiResponse} error envelope with user-safe messages only
 * (PROJECT-CONVENTIONS §3.1 — never leak internal exception text). The list grows as the domain
 * defines its own exceptions (e.g. a NotFoundException → 404) in later slices.
 */
@RestControllerAdvice(basePackages = "com.erp.api")
public class GlobalExceptionHandler {

    /** Bean-validation failures on request DTOs → 400 with field messages. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.error(messages));
    }

    /** Entity addressed by uid does not exist → 404. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    /** Domain rule violated (duplicate code, invalid default, etc.) → 409. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    /** Password fails policy → 400 with the actionable rule. */
    @ExceptionHandler(com.erp.platform.security.password.WeakPasswordException.class)
    public ResponseEntity<ApiResponse<Void>> handleWeakPassword(
            com.erp.platform.security.password.WeakPasswordException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    /** Bad credentials / auth failure → 401, generic message (no username/password hint). */
    @ExceptionHandler(com.erp.platform.security.auth.AuthenticationException.class)
    public ResponseEntity<ApiResponse<Void>> handleAuth(
            com.erp.platform.security.auth.AuthenticationException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.error(ex.getMessage()));
    }

    /** Bad input the caller can fix → 400. */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(ex.getMessage()));
    }

    /** Anything unexpected → 500 with a generic message; details go to logs, not the client. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        // TODO(logging): wire a logger in Slice 0 follow-up; do not echo ex.getMessage() to client.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred."));
    }
}
