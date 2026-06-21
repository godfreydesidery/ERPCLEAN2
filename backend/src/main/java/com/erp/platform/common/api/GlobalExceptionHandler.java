package com.erp.platform.common.api;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * Translates exceptions into the {@link ApiResponse} error envelope with user-safe messages only
 * (PROJECT-CONVENTIONS §3.1 — never leak internal exception text). The list grows as the domain
 * defines its own exceptions (e.g. a NotFoundException → 404) in later slices.
 *
 * <p>Scoped to the full {@code com.erp} base package (no {@code basePackages} restriction) so that
 * exceptions raised by the DispatcherServlet dispatch path — notably
 * {@link NoHandlerFoundException} and {@link HttpRequestMethodNotSupportedException} — are caught
 * here rather than falling through to Spring's Whitelabel error path. The existing
 * {@link com.erp.platform.common.api.ApiResponseAdvice} retains its {@code com.erp.api} scope for
 * body-wrapping; this handler is a separate concern (exception → response, not body-wrapping).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // PostgreSQL SQLState codes used to discriminate constraint violation types.
    private static final String SQLSTATE_UNIQUE      = "23505";
    private static final String SQLSTATE_FK          = "23503";
    private static final String SQLSTATE_CHECK       = "23514";
    private static final String SQLSTATE_NOT_NULL_DB = "23502";
    private static final String SQLSTATE_TRUNCATION  = "22001";
    private static final String SQLSTATE_NUMERIC_OVF = "22003";

    /**
     * Matches "No enum constant com.example.SomeEnum.VALUE" so we can strip the FQCN.
     * Group 1 = simple type name, group 2 = supplied value (theme #10 info-disclosure fix).
     */
    private static final Pattern ENUM_CONSTANT_PATTERN =
            Pattern.compile("No enum constant [\\w$.]+\\.([\\w$.]+)\\.([\\w]+)");

    /** Prefix shared by all enum-mismatch messages. */
    private static final String INVALID_VALUE_PREFIX = "Invalid value '";

    // -------------------------------------------------------------------------
    // Existing handlers (preserved verbatim)
    // -------------------------------------------------------------------------

    /** Bean-validation failures on request DTOs → 400 with field messages. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> messages = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        return ResponseEntity.badRequest().body(ApiResponse.error(messages));
    }

    /**
     * Missing or un-coercible required query param / path-var type mismatch → 400.
     * {@link HttpMessageNotReadableException} is handled in its own method below to scrub enum
     * FQCN leaks (theme #10).
     */
    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(Exception ex) {
        String msg = switch (ex) {
            case MissingServletRequestParameterException e ->
                    "Missing required request parameter: " + e.getParameterName();
            case MethodArgumentTypeMismatchException e ->
                    "Invalid value for parameter '" + e.getName() + "'.";
            default -> "Invalid request.";
        };
        return ResponseEntity.badRequest().body(ApiResponse.error(msg));
    }

    /** Entity addressed by uid does not exist → 404. */
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * No controller or resource handler matched the request path → 404 in the standard envelope.
     *
     * <p>Without this handler the DispatcherServlet forwards to the Whitelabel {@code /error}
     * path, which emits a raw Spring error body containing the stack trace (security defects
     * RBAC-SECURITY-NEGATIVE-017 and -046). This handler intercepts the exception before it
     * reaches that path and returns a clean, user-safe 404 response. Requires
     * {@code spring.mvc.throw-exception-if-no-handler-found=true} in application.yml; the
     * {@code ApiNotFoundController} catch-all ensures the exception is raised for
     * {@code /api/**} paths even when the SPA resource handler consumes the dispatch first.
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFound(NoHandlerFoundException ex) {
        log.debug("No handler found: {} {}", ex.getHttpMethod(), ex.getRequestURL());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("The requested resource was not found."));
    }

    /**
     * HTTP method not allowed on an existing endpoint → 405 in the standard envelope.
     *
     * <p>Without this handler Spring emits the Whitelabel error body with a stack trace (security
     * defect IAM-ORG-060). Logged at DEBUG because 405 is a caller error, not a server fault.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex) {
        log.debug("Method not allowed: {} — allowed: {}", ex.getMethod(), ex.getSupportedMethods());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("HTTP method not allowed for this endpoint."));
    }

    /** Domain rule violated (duplicate code, invalid default, etc.) → 409. */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    /** Lacks permission, or acting outside the active scope (service-layer ScopeGuard) → 403. */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Method-security denial from {@code @PreAuthorize} (Spring throws AuthorizationDeniedException,
     * a subtype of AccessDeniedException) → 403. Without this, the catch-all below would map it to
     * 500. The message is generic — never name the missing permission (no enumeration), matching
     * {@link com.erp.platform.security.config.SecurityErrorResponder} for filter-level denials.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("You do not have permission to perform this action."));
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

    /** Currency not enabled for the company/branch scope → 422 (ADR-0039 D-7/D-8). */
    @ExceptionHandler(com.erp.modules.fx.domain.exception.CurrencyNotEnabledException.class)
    public ResponseEntity<ApiResponse<Void>> handleCurrencyNotEnabled(
            com.erp.modules.fx.domain.exception.CurrencyNotEnabledException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Business-rule state conflict (operation not valid for the resource's current state) → 409.
     * e.g. finalising an unpaid or empty invoice, mutating a finalised invoice, voiding a
     * non-finalised invoice. Surfaces the rule message to the caller instead of a generic 500.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalState(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.error(ex.getMessage()));
    }

    /**
     * Optimistic-locking conflict — two transactions updated the same {@code @Version}-ed row
     * concurrently (Spring's {@code OptimisticLockingFailureException}, which wraps Hibernate's
     * {@code StaleObjectStateException}; also the raw Hibernate/JPA variants) → 409 Conflict, never
     * 500. This is a TRANSIENT, retryable conflict (e.g. concurrent stock-on-hand updates), not a
     * server fault — the caller should reload and retry. Logged at WARN (expected under contention).
     */
    @ExceptionHandler({
            org.springframework.dao.OptimisticLockingFailureException.class,
            org.hibernate.StaleObjectStateException.class,
            jakarta.persistence.OptimisticLockException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleOptimisticLock(Exception ex) {
        log.warn("Optimistic-lock conflict (concurrent update): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(
                        "This record was modified by another transaction. Please reload and try again."));
    }

    // -------------------------------------------------------------------------
    // New handlers — cross-cutting theme fixes (adversarial issues #1 / #10 /
    // #47 + every MEDIUM whose actual is "HTTP 500 on bad-but-validatable input")
    // -------------------------------------------------------------------------

    /**
     * DB / JPA constraint violations that were not caught at the service layer → 4xx, never 500.
     *
     * <p>Discrimination by PostgreSQL SQLState (walked from the cause chain):
     * <ul>
     *   <li>{@code 23505} unique_violation → 409 Conflict</li>
     *   <li>{@code 23503} foreign_key_violation → 409 Conflict (referenced row missing)</li>
     *   <li>{@code 23514} check_violation → 400 Bad Request</li>
     *   <li>{@code 23502} not_null_violation (DB-level) → 400 Bad Request</li>
     *   <li>{@code 22001} string_data_right_truncation → 400 Bad Request</li>
     *   <li>{@code 22003} numeric_value_out_of_range → 400 Bad Request</li>
     *   <li>anything else → 409 (safe default; still prevents 500)</li>
     * </ul>
     *
     * <p>The user message is ALWAYS generic — raw SQL / constraint names / column names are NEVER
     * echoed (PROJECT-CONVENTIONS §3.1). The full exception is logged at WARN so it remains
     * diagnosable. Root-cause hints (adversarial issues #16, #18, #22, #26, #27, #28, #42, #47):
     * per-field service-layer guards are the preferred first-line fix; this handler is the safety
     * net for any that slip through.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleDataIntegrity(
            DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getMessage());
        String sqlState = extractSqlState(ex);
        return switch (sqlState) {
            case SQLSTATE_UNIQUE ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ApiResponse.error("A record with the same unique identifier already exists."));
            case SQLSTATE_FK ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ApiResponse.error("The referenced record does not exist or has been removed."));
            case SQLSTATE_CHECK, SQLSTATE_NOT_NULL_DB, SQLSTATE_TRUNCATION, SQLSTATE_NUMERIC_OVF ->
                    ResponseEntity.badRequest()
                            .body(ApiResponse.error("The supplied value violates a data constraint. Check field formats, lengths, and numeric ranges."));
            default ->
                    ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(ApiResponse.error("The request conflicts with existing data or a data rule."));
        };
    }

    /**
     * Walks the exception cause chain looking for a {@link SQLException} and returns its SQLState,
     * or an empty string when none is found.
     */
    private static String extractSqlState(Throwable ex) {
        Throwable t = ex;
        while (t != null) {
            if (t instanceof SQLException sqle) {
                String state = sqle.getSQLState();
                return state != null ? state : "";
            }
            t = t.getCause();
        }
        return "";
    }

    /**
     * Wrong Content-Type on a POST/PUT → 415 Unsupported Media Type.
     *
     * <p>Without this, Spring's {@link HttpMediaTypeNotSupportedException} fell through to the
     * catch-all and returned a generic 500. Adversarial issue #47 (unauthenticated-reachable on
     * {@code /auth/login} with {@code Content-Type: application/x-www-form-urlencoded}).
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex) {
        log.debug("Unsupported media type: {}", ex.getContentType());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("Content-Type not supported. Use application/json."));
    }

    /**
     * Malformed JSON body or an unparseable enum value → 400.
     *
     * <p>Theme #10 (information-disclosure): Jackson's default enum-deserialization error embeds
     * the fully-qualified class name, e.g.
     * {@code "No enum constant com.erp.modules.hr.domain.enums.StatutoryBasis.XXXX"}.
     * This handler scrubs the FQCN and produces a clean field-level message such as
     * {@code "Invalid value 'XXXX' for field 'basis' (StatutoryBasis)."}.
     *
     * <p>The internal {@code "date null"} NPE message leaking through the GL fiscal-period lookup
     * is also suppressed — that path now returns a generic bad-body message instead.
     *
     * <p>Note: this handler is separate from the multi-exception {@link #handleBadRequest} group
     * above precisely to allow this richer, type-safe inspection.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleMessageNotReadable(
            HttpMessageNotReadableException ex) {
        String msg = deriveReadableMessage(ex);
        return ResponseEntity.badRequest().body(ApiResponse.error(msg));
    }

    /** Extracts a user-safe message from an {@link HttpMessageNotReadableException}. */
    private static String deriveReadableMessage(HttpMessageNotReadableException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof InvalidFormatException ife && ife.getTargetType() != null
                && ife.getTargetType().isEnum()) {
            return buildEnumMessage(ife);
        }
        // Defensive fallback: strip FQCN from any remaining cause message.
        if (cause != null && cause.getMessage() != null) {
            Matcher m = ENUM_CONSTANT_PATTERN.matcher(cause.getMessage());
            if (m.find()) {
                return INVALID_VALUE_PREFIX + m.group(2) + "' for " + m.group(1) + ".";
            }
        }
        return "Malformed request body.";
    }

    /**
     * Builds the clean enum-mismatch message from Jackson's {@link InvalidFormatException}.
     * Example output: {@code "Invalid value 'XXXX' for field 'basis' (StatutoryBasis)."}
     */
    private static String buildEnumMessage(InvalidFormatException ife) {
        String typeName = ife.getTargetType().getSimpleName();
        String value    = ife.getValue() != null ? String.valueOf(ife.getValue()) : "null";
        String fieldPath = buildFieldPath(ife);
        if (fieldPath.isBlank()) {
            return INVALID_VALUE_PREFIX + value + "' for " + typeName + ".";
        }
        return INVALID_VALUE_PREFIX + value + "' for field '" + fieldPath + "' (" + typeName + ").";
    }

    /** Reconstructs the dot-notation field path from Jackson's reference chain. */
    private static String buildFieldPath(InvalidFormatException ife) {
        if (ife.getPath().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (com.fasterxml.jackson.databind.JsonMappingException.Reference ref : ife.getPath()) {
            if (ref.getFieldName() != null) {
                if (!sb.isEmpty()) {
                    sb.append('.');
                }
                sb.append(ref.getFieldName());
            } else {
                sb.append('[').append(ref.getIndex()).append(']');
            }
        }
        return sb.toString();
    }

    /**
     * NullPointerException guard — returns a clean 500 envelope WITHOUT leaking the stack or
     * message text. Logged at ERROR for diagnosability. The preferred fix for per-field NPEs is a
     * null-guard at the service/controller level; this handler is the safety net.
     *
     * <p>Risk note: any NPE not caught closer to its origin will produce this generic message
     * instead of a more specific domain error. Reviewers should verify that callers receive
     * actionable guidance from the service layer before an NPE can escape.
     */
    @ExceptionHandler(NullPointerException.class)
    public ResponseEntity<ApiResponse<Void>> handleNpe(NullPointerException ex) {
        log.error("NullPointerException reached exception handler — investigate the calling path", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred."));
    }

    /**
     * Anything unexpected → 500 with a generic message. The full stack is logged at ERROR (with the
     * request's MDC context — correlation id, user, company, branch — when present) so an unexpected
     * 500 is never invisible in the logs (ISSUES-REGISTER #4). The exception text is NEVER echoed to
     * the client — only the generic envelope is returned (PROJECT-CONVENTIONS §3.1, no internal leak).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
        log.error("Unhandled exception processing request", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("An unexpected error occurred."));
    }
}
