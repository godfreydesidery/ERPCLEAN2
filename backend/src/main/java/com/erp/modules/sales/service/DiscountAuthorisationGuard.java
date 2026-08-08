package com.erp.modules.sales.service;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.sales.domain.dto.DiscountPolicyDto;
import com.erp.modules.sales.domain.enums.DiscountApprovalAction;
import com.erp.modules.sales.domain.enums.DiscountRefusalCode;
import com.erp.modules.sales.domain.exception.DiscountApprovalException;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enforces the per-company "manager-authorised discount" policy (K7, Kilimanjaro 2026-08-08) on
 * every path that can put a discount on a sales-invoice line.
 *
 * <p><b>Why a ceiling and not a permission.</b> A cashier must be able to knock a few percent off to
 * close a sale — that is the job. What the client asked to stop is the unbounded case: today
 * {@link DiscountValidator} accepts anything in [0, 100], so "100% off" is valid input and
 * {@link InvoiceTotalsCalculator} quietly floors the line to zero. The policy is therefore a
 * configurable ceiling a cashier may apply alone, and a manager step-up above it.
 *
 * <p><b>Both surfaces, one guard.</b> A till-only control would be decorative: the identical ungated
 * input exists on the web invoice screen, and the POS sale itself is built out of
 * {@code SalesInvoiceService.addLine} calls. Enforcing inside add-line/update-line therefore covers
 * the web path and the POS path with one implementation that cannot drift between them. (Quotations
 * are deliberately out of scope — a quote is a proposal, not a sale, and it has to pass through
 * {@code accept} → order → invoice before any money moves, where this guard applies.)
 *
 * <p><b>OFF unless a company opts in.</b> {@link DiscountPolicyProvider} reads
 * {@code sales_settings.discount_approval_action} (V95), which defaults to OFF for every existing
 * row and every newly seeded one; this guard returns on the first policy branch when the answer is
 * OFF. No tenant sees any change until someone deliberately sets a stance on the Sales Settings
 * screen.
 *
 * <p><b>What "authorised" means, precisely — and what it does not.</b> The client first calls
 * {@code POST /api/v1/auth/verify-authority} with the manager's own username and password and the
 * permission code {@code SALES.DISCOUNT.OVERRIDE}; that call proves the password, is audited, is
 * rate-limited, and issues no token (the cashier stays logged in). It hands back the manager's uid,
 * which the client then puts on the line request. This guard independently re-resolves that uid and
 * requires the named user to be active AND to genuinely hold {@code SALES.DISCOUNT.OVERRIDE} in the
 * INVOICE's company — so a flag alone is never enough and a foreign-company manager authorises
 * nothing.
 *
 * <p><b>Why its own permission code and not {@code SALES.INVOICE.OVERRIDE}.</b> That code means
 * "may re-price a line, or set a non-default agent" — a different authority that happens to sit in
 * the same bundles today. Reusing it would have made "who may wave a discount through" impossible to
 * grant or revoke on its own, which is precisely the control the client asked for.
 * {@code SALES.DISCOUNT.OVERRIDE} is seeded in {@code R__seed_permissions.sql} and granted to
 * SALES_MANAGER and BRANCH_MANAGER — never to CASHIER, or a cashier could approve their own
 * discount by retyping their own password.
 *
 * <p>The honest limit: a uid on a request is not cryptographic proof that the password was typed
 * seconds earlier. A hostile client could name a manager it never asked. Two things bound that —
 * the named manager must really hold the authority (so it cannot be any colleague), and both the
 * step-up attempt and the override are audited by name, so the fabrication is on the record.
 * Closing it completely needs a short-lived, single-use grant handle minted by the step-up service
 * and redeemed here; that is a small follow-up, and it is noted as such rather than pretended away.
 */
@Component
public class DiscountAuthorisationGuard {

    private static final Logger log = LoggerFactory.getLogger(DiscountAuthorisationGuard.class);

    /** The permission a manager must hold to wave a discount through. Seeded (R__seed_permissions). */
    public static final String DISCOUNT_OVERRIDE_PERMISSION = "SALES.DISCOUNT.OVERRIDE";

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final String TARGET_TYPE = "sales_invoice_lines";

    private final DiscountPolicyProvider policies;
    private final AppUserRepository users;
    private final PermissionResolver permissionResolver;
    private final AuditService audit;

    public DiscountAuthorisationGuard(DiscountPolicyProvider policies,
                                      AppUserRepository users,
                                      PermissionResolver permissionResolver,
                                      AuditService audit) {
        this.policies = policies;
        this.users = users;
        this.permissionResolver = permissionResolver;
        this.audit = audit;
    }

    /**
     * One discount, as the guard needs to see it.
     *
     * @param companyId           the tenant, taken from the LOADED invoice — never from the request
     * @param invoiceId           audit target id
     * @param invoiceUid          audit target uid
     * @param productName         for the user-facing message only (never an id — error hygiene)
     * @param lineGross           unit price × quantity, i.e. the base the discount comes off. Uses
     *                            the same basis as {@link InvoiceTotalsCalculator}, so an inclusive
     *                            price list compares gross-against-gross and the ratio is unaffected
     * @param discountAmount      the requested absolute discount (nullable)
     * @param discountPercent     the requested percentage discount (nullable, mutually exclusive)
     * @param authoriserUid       uid returned by a successful manager step-up, or null/blank
     */
    public record DiscountRequest(Long companyId, Long invoiceId, String invoiceUid,
                                  String productName, BigDecimal lineGross,
                                  BigDecimal discountAmount, BigDecimal discountPercent,
                                  String authoriserUid) {}

    /**
     * Applies the company's discount policy to one line.
     *
     * @return the authorising user's internal id when a manager approved an over-ceiling discount,
     *         otherwise {@code null}. Callers stamp it on
     *         {@code sales_invoice_lines.discount_authorised_by} (V95) and record it in the audit
     *         detail, so the approval is queryable on the document itself, not only in the trail.
     * @throws ConflictException with a friendly, internals-free message when the policy refuses
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Long authoriseLineDiscount(DiscountRequest req) {
        // Cheapest question first, and the one that is "no" for almost every line ever rung: is
        // there a discount at all? Answering it before the policy lookup keeps the ordinary line
        // free of a settings query — which matters now that the policy is a real read against
        // sales_settings (V95) and a POS basket calls this once per line. The outcome is identical
        // either way: a zero discount is never checked under ANY action, OFF included.
        BigDecimal requested = effectiveDiscountPercent(
                req.lineGross(), req.discountAmount(), req.discountPercent());
        if (requested.signum() <= 0) {
            return null;                                   // no discount, nothing to authorise
        }

        DiscountPolicyDto policy = policies.policyFor(req.companyId());
        if (policy.isOff()) {
            return null;                                   // the default — unchanged behaviour
        }

        BigDecimal ceiling = policy.ceiling();
        if (requested.compareTo(ceiling) <= 0) {
            return null;                                   // within what a cashier may do alone
        }

        return switch (policy.action()) {
            case WARN -> {
                log.warn("Discount of {}% exceeds the {}% ceiling on invoice {} (company {}) — "
                                + "allowed under WARN.",
                        plain(requested), plain(ceiling), req.invoiceUid(), req.companyId());
                audit.record(AuditEvent.of(AuditActions.SALES_DISCOUNT_WARNING, TARGET_TYPE,
                                req.invoiceId(), req.invoiceUid())
                        .detail(detail(req, requested, ceiling, DiscountApprovalAction.WARN,
                                null, "ALLOWED_OVER_CEILING")));
                yield null;
            }
            case APPROVE -> approve(req, requested, ceiling);
            case BLOCK -> throw refuse(req, requested, ceiling, DiscountApprovalAction.BLOCK,
                    "BLOCKED", null, DiscountRefusalCode.DISCOUNT_ABOVE_CEILING,
                    blockedMessage(req.productName(), ceiling));
            // OFF returned above; the switch stays exhaustive so a new stance cannot be forgotten.
            case OFF -> null;
        };
    }

    // -------------------------------------------------------------------------

    /**
     * APPROVE: the discount goes through only when a named, active user who genuinely holds
     * {@code SALES.DISCOUNT.OVERRIDE} in THIS invoice's company was supplied as the authoriser.
     *
     * <p>Every refusal here says the same thing to the operator regardless of which check failed —
     * unknown uid, deactivated account and "that person cannot approve discounts" are one message.
     * A till is not a place to enumerate who does and does not have authority.
     */
    private Long approve(DiscountRequest req, BigDecimal requested, BigDecimal ceiling) {
        String uid = req.authoriserUid();
        if (uid == null || uid.isBlank()) {
            throw refuse(req, requested, ceiling, DiscountApprovalAction.APPROVE,
                    "NO_APPROVAL_SUPPLIED", null,
                    DiscountRefusalCode.DISCOUNT_APPROVAL_REQUIRED,
                    needsApprovalMessage(req.productName(), ceiling));
        }

        AppUser authoriser = users.findByUid(uid.trim()).orElse(null);
        if (authoriser == null || !authoriser.isActive()) {
            log.warn("Discount approval rejected on invoice {} (company {}): the supplied authoriser "
                    + "is unknown or inactive.", req.invoiceUid(), req.companyId());
            throw refuse(req, requested, ceiling, DiscountApprovalAction.APPROVE,
                    "AUTHORISER_UNAVAILABLE", null,
                    DiscountRefusalCode.DISCOUNT_APPROVAL_NOT_ACCEPTED,
                    approvalNotAcceptedMessage());
        }

        RequestContext.Principal caller = RequestContext.get();

        // TWO-PERSON INTEGRITY: an over-the-shoulder approval involves a SECOND person by
        // definition, so the operator ringing the sale can never be their own authoriser — even
        // when they legitimately hold SALES.DISCOUNT.OVERRIDE. Without this, a sales manager
        // serving a customer could grant themselves an unlimited discount and the audit trail
        // would read as a supervised approval. Not a privilege escalation (only a genuine
        // permission-holder can reach it) but it hollows out the control, and it matched
        // StepUpAuthServiceImpl.verifyAuthority's rule nowhere until now — the same integrity
        // model must mean the same thing at every gate.
        if (caller != null && caller.userId() != null
                && caller.userId().equals(authoriser.getId())) {
            log.warn("Discount approval rejected on invoice {} (company {}): the operator named "
                    + "themselves as the authoriser.", req.invoiceUid(), req.companyId());
            throw refuse(req, requested, ceiling, DiscountApprovalAction.APPROVE,
                    "SELF_APPROVAL", authoriser.getUsername(),
                    DiscountRefusalCode.DISCOUNT_APPROVAL_NOT_ACCEPTED,
                    approvalNotAcceptedMessage());
        }

        // Company comes from the LOADED invoice; the branch is the caller's active one (the till the
        // sale is being rung at). A caller-supplied company is never trusted here.
        RequestContext.Principal authoriserInInvoiceScope = new RequestContext.Principal(
                authoriser.getId(),
                authoriser.getUsername(),
                authoriser.isRoot(),
                req.companyId(),
                caller != null ? caller.branchId() : null,
                caller != null ? caller.ip() : null);

        boolean holds = permissionResolver.hasPermission(
                authoriserInInvoiceScope, DISCOUNT_OVERRIDE_PERMISSION, System.currentTimeMillis());
        if (!holds) {
            log.warn("Discount approval rejected on invoice {} (company {}): the named authoriser "
                    + "does not hold {}.", req.invoiceUid(), req.companyId(),
                    DISCOUNT_OVERRIDE_PERMISSION);
            throw refuse(req, requested, ceiling, DiscountApprovalAction.APPROVE,
                    "AUTHORISER_LACKS_PERMISSION", authoriser.getUsername(),
                    DiscountRefusalCode.DISCOUNT_APPROVAL_NOT_ACCEPTED,
                    approvalNotAcceptedMessage());
        }

        audit.record(AuditEvent.of(AuditActions.SALES_DISCOUNT_OVERRIDE, TARGET_TYPE,
                        req.invoiceId(), req.invoiceUid())
                .detail(detail(req, requested, ceiling, DiscountApprovalAction.APPROVE,
                        authoriser.getUsername(), "GRANTED")));
        return authoriser.getId();
    }

    /**
     * Audits a refusal and returns the exception for the caller to throw.
     *
     * <p>Uses {@code recordIndependent} (REQUIRES_NEW) deliberately: the exception rolls the sale
     * transaction back, so an audit row written in that transaction would roll back with it and the
     * attempt would leave no trace at all. "A cashier tried 60% off and was refused" is exactly the
     * kind of fact a shrinkage investigation needs — losing it is worse than the extra write.
     *
     * <p>Returned rather than thrown so the call sites read {@code throw refuse(...)}, which keeps
     * the compiler's definite-assignment and exhaustiveness analysis intact.
     *
     * <p>The returned exception carries a {@link DiscountRefusalCode} beside the friendly message
     * (UAT finding #13). The client needs to know whether a supervisor can still rescue this sale;
     * deciding that by searching the English sentence for the word "approval" breaks the moment
     * anyone rewords it, and the failure is silent — the button just stops appearing.
     */
    private ConflictException refuse(DiscountRequest req, BigDecimal requested, BigDecimal ceiling,
                                     DiscountApprovalAction action, String outcome,
                                     String authoriserUsername, DiscountRefusalCode code,
                                     String message) {
        audit.recordIndependent(AuditEvent.of(AuditActions.SALES_DISCOUNT_WARNING, TARGET_TYPE,
                        req.invoiceId(), req.invoiceUid())
                .detail(detail(req, requested, ceiling, action, authoriserUsername, outcome)));
        return new DiscountApprovalException(code, message);
    }

    /**
     * The requested discount as a percentage of the line's gross, on the same basis
     * {@link InvoiceTotalsCalculator} uses to apply it.
     *
     * <p>An absolute amount against a non-positive gross is reported as 100%: there is no meaningful
     * ratio, and "give it away for free" is precisely the case the ceiling exists to catch, so it
     * must not fall through as "0% — fine".
     */
    static BigDecimal effectiveDiscountPercent(BigDecimal lineGross,
                                               BigDecimal discountAmount,
                                               BigDecimal discountPercent) {
        if (discountPercent != null && discountPercent.signum() > 0) {
            return discountPercent;
        }
        if (discountAmount == null || discountAmount.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (lineGross == null || lineGross.signum() <= 0) {
            return HUNDRED;
        }
        return discountAmount.multiply(HUNDRED).divide(lineGross, 4, RoundingMode.HALF_UP);
    }

    private Map<String, Object> detail(DiscountRequest req, BigDecimal requested,
                                       BigDecimal ceiling, DiscountApprovalAction action,
                                       String authoriserUsername, String outcome) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("action", action.name());
        detail.put("outcome", outcome);
        detail.put("product", req.productName() == null ? "" : req.productName());
        detail.put("discountPercent", plain(requested));
        detail.put("ceilingPercent", plain(ceiling));
        detail.put("discountAmount",
                req.discountAmount() != null ? req.discountAmount().toPlainString() : "");
        detail.put("authoriserUid", req.authoriserUid() == null ? "" : req.authoriserUid());
        detail.put("authoriserUsername", authoriserUsername == null ? "" : authoriserUsername);
        detail.put("at", Instant.now().toString());
        return detail;
    }

    // -------------------------------------------------------------------------
    // User-facing messages — friendly, actionable, zero internal detail.
    //
    // Two deliberate inclusions. The CEILING is quoted: it is the operator's own store policy, and a
    // rejection that will not say what the limit is just produces a second identical failed attempt.
    // The PRODUCT NAME is quoted: a ten-line basket needs to say which line to fix. Neither is an
    // internal identifier — no uid, no id, no permission code, no table name.
    // -------------------------------------------------------------------------

    private static String needsApprovalMessage(String productName, BigDecimal ceiling) {
        return subject(productName) + " is above " + plain(ceiling)
                + "% and needs a manager's approval. "
                + "Ask a supervisor to authorise it, then try again.";
    }

    private static String approvalNotAcceptedMessage() {
        return "That approval was not accepted. Ask a supervisor with discount authority to "
                + "approve this discount.";
    }

    private static String blockedMessage(String productName, BigDecimal ceiling) {
        return subject(productName) + " is above " + plain(ceiling) + "%, which is not allowed. "
                + "Reduce the discount to continue.";
    }

    private static String subject(String productName) {
        return productName == null || productName.isBlank()
                ? "This discount"
                : "The discount on " + productName;
    }

    /** "10.00" → "10", "7.5000" → "7.5" — a percentage a human would write. */
    private static String plain(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0).toPlainString() : stripped.toPlainString();
    }
}
