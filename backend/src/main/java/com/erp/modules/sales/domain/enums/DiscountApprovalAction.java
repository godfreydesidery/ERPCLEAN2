package com.erp.modules.sales.domain.enums;

/**
 * Per-company stance on a line discount that exceeds the cashier's ceiling (K7, Kilimanjaro
 * 2026-08-08). Deliberately an ACTION rather than a boolean, exactly like
 * {@link BelowCostAction}: the owner asked for "manager-authorised discounts", but every business
 * that asks for that eventually wants one of the four stances below, and a boolean can express two.
 *
 * <p><b>{@link #OFF} is the default and means today's behaviour, unchanged.</b> It is the V95 column
 * default, so no company sees any difference until someone deliberately turns the policy on.
 */
public enum DiscountApprovalAction {

    /** No ceiling is enforced. The pre-K7 behaviour, and what every company reads until it opts in. */
    OFF,

    /** Allow the discount, but record that it went out over the ceiling (audit only, no friction). */
    WARN,

    /**
     * Reject unless a manager authorised it via the step-up endpoint
     * ({@code POST /api/v1/auth/verify-authority}) and that manager holds
     * {@code SALES.DISCOUNT.OVERRIDE}. The mode the client asked for.
     */
    APPROVE,

    /** Always reject over the ceiling — no override exists. For a chain that prices centrally. */
    BLOCK
}
