package com.erp.platform.common.api;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * Wraps every controller return value in {@link ApiResponse} so controllers stay free of envelope
 * boilerplate (PROJECT-CONVENTIONS §3.1). Already-wrapped values pass through unchanged.
 *
 * <p>Scoped to {@code com.erp.api} controllers. Actuator and error responses are left alone.
 */
@RestControllerAdvice(basePackages = "com.erp.api")
public class ApiResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body,
                                  MethodParameter returnType,
                                  MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request,
                                  ServerHttpResponse response) {
        if (body instanceof ApiResponse<?>) {
            return body;
        }
        // String responses use a dedicated converter that would choke on a wrapped object;
        // return as-is and let dedicated string endpoints (none yet) opt out if needed.
        if (body instanceof String) {
            return body;
        }
        return ApiResponse.ok(body);
    }
}
