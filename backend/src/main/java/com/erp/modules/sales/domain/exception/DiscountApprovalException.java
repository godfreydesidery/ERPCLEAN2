package com.erp.modules.sales.domain.exception;

import com.erp.modules.sales.domain.enums.DiscountRefusalCode;
import com.erp.platform.common.api.ConflictException;

/**
 * A line discount the company's policy would not take (K7), carrying a machine-readable
 * {@link DiscountRefusalCode} alongside the friendly message so a client can tell "a supervisor can
 * fix this" from "nobody can" without reading the prose (UAT finding #13).
 *
 * <p>Extends {@link ConflictException} deliberately: every existing caller and handler keeps
 * treating it exactly as the 409 it already was, so nothing that ignores the code changes behaviour.
 * The code is additive.
 *
 * <p>The message stays the cashier-facing sentence — friendly, naming the ceiling and the product,
 * and free of ids, permission codes and table names.
 */
public class DiscountApprovalException extends ConflictException {

    private final DiscountRefusalCode code;

    public DiscountApprovalException(DiscountRefusalCode code, String message) {
        super(message);
        this.code = code;
    }

    public DiscountRefusalCode getCode() {
        return code;
    }
}
