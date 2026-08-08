import { HttpErrorResponse } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { DiscountApprovalAction } from '../models/sales.model';

/**
 * What to do when a line discount exceeds the ceiling. Defined once with the rest of the sales wire
 * contract and re-exported here so existing importers keep working.
 */
export type { DiscountApprovalAction };

/** A company's resolved discount-approval policy. Mirrors the backend `DiscountPolicyDto`. */
export interface DiscountPolicy {
  readonly action: DiscountApprovalAction;
  /**
   * The percentage a cashier may apply unaided. `null` means "not configured", which under
   * APPROVE/BLOCK is read as ZERO — the same reading the server uses. Turning the policy on must
   * *do* something; treating an unset ceiling as "unlimited" is exactly how the negative-stock
   * toggle managed to look enabled while allowing everything.
   */
  readonly maxDiscountPercent: number | null;
}

/**
 * The permission a supervisor must hold to wave a discount through. Seeded in
 * `R__seed_permissions.sql` and granted to SALES_MANAGER / BRANCH_MANAGER, never to CASHIER.
 *
 * MUST equal `DiscountAuthorisationGuard.DISCOUNT_OVERRIDE_PERMISSION` on the server: this is the
 * code the manager-approval dialog sends to `/auth/verify-authority`, and the server independently
 * re-checks the SAME code before accepting the approval. If the two ever drift, a manager passes the
 * password prompt and is then refused by the guard — which reads to the till as "your own manager is
 * not allowed to approve this".
 */
export const DISCOUNT_OVERRIDE_PERMISSION = 'SALES.DISCOUNT.OVERRIDE';

const OFF: DiscountPolicy = { action: 'OFF', maxDiscountPercent: null };

/**
 * The machine-readable refusal tokens the server sends beside the friendly message. These MUST equal
 * the backend `DiscountRefusalCode` names — they are the wire contract.
 *
 * Replaces matching the refusal's English prose (UAT finding #13): the checkout used to look for the
 * words "discount" and "approval" in the server's sentence, so a rewording — or a translation — made
 * the "Ask a supervisor" button silently disappear and left the cashier at a dead end.
 */
export const DISCOUNT_APPROVAL_REQUIRED = 'DISCOUNT_APPROVAL_REQUIRED';
export const DISCOUNT_APPROVAL_NOT_ACCEPTED = 'DISCOUNT_APPROVAL_NOT_ACCEPTED';
export const DISCOUNT_ABOVE_CEILING = 'DISCOUNT_ABOVE_CEILING';

/** The error envelope shape this file reads. Everything on it is optional by design. */
interface RefusalEnvelope {
  readonly data?: { readonly code?: string; readonly errorCode?: string | null } | null;
}

/**
 * Client-side mirror of the backend's `DiscountPolicyProvider` (K7) — the ONE place this app
 * decides whether a discount needs a supervisor.
 *
 * <b>OFF today, for every company</b>, exactly as the server ships it, so nothing changes for any
 * tenant: {@link needsApproval} returns false on its first branch and the checkout behaves as it
 * does now. The enforcement itself is server-side and unconditional — this class only decides
 * whether to ask for the approval BEFORE sending, so a cashier is not bounced by a refusal they
 * could have satisfied at the till.
 *
 * <b>The seam.</b> When the `sales_settings` columns are approved (`discount_approval_action`,
 * `max_discount_percent`) the backend will expose them and this method becomes a lookup:
 * inject the sales-settings client, read the active company's row, and return
 * `{ action, maxDiscountPercent }`. Nothing else in the app has to change — the guard, the prompt
 * and the wire field are already in place. Deliberately not wired to `GET /sales-settings/...`
 * today: that endpoint is gated SALES.SETTINGS.MANAGE, which a cashier does not hold, so calling it
 * from the till would 403 on every sale.
 */
@Injectable({ providedIn: 'root' })
export class DiscountPolicyService {
  /** The policy in force for the active company. */
  policy(): DiscountPolicy {
    return OFF;
  }

  /** True when nothing needs checking, so callers can return before doing any work. */
  isOff(policy: DiscountPolicy = this.policy()): boolean {
    return policy.action === 'OFF';
  }

  /** The ceiling to compare against; a policy that is on but unconfigured allows nothing. */
  ceiling(policy: DiscountPolicy = this.policy()): number {
    return policy.maxDiscountPercent ?? 0;
  }

  /**
   * The requested discount as a percentage of the line's gross, on the same basis the server uses
   * (`unitPrice × quantity`) so an amount-only discount cannot slip past a percentage ceiling.
   *
   * An absolute amount against a non-positive line value is reported as 100%: there is no
   * meaningful ratio, and "give it away" is precisely what the ceiling exists to catch, so it must
   * not fall through as "0% — fine".
   */
  effectiveDiscountPercent(
    lineGross: number,
    discountAmount: number | null,
    discountPercent: number | null,
  ): number {
    if (discountPercent !== null && discountPercent > 0) return discountPercent;
    if (discountAmount === null || discountAmount <= 0) return 0;
    if (!Number.isFinite(lineGross) || lineGross <= 0) return 100;
    return (discountAmount * 100) / lineGross;
  }

  /**
   * True when this discount may not go through without a supervisor's approval.
   *
   * WARN and BLOCK deliberately return false: WARN allows it (the server just records the fact) and
   * BLOCK cannot be unlocked by anyone, so prompting for an approval that would be refused anyway
   * would be a lie. Only APPROVE is satisfiable at the till.
   */
  needsApproval(
    lineGross: number,
    discountAmount: number | null,
    discountPercent: number | null,
    policy: DiscountPolicy = this.policy(),
  ): boolean {
    if (policy.action !== 'APPROVE') return false;
    const requested = this.effectiveDiscountPercent(lineGross, discountAmount, discountPercent);
    if (requested <= 0) return false;
    return requested > this.ceiling(policy);
  }

  // ── Reading a server refusal (UAT finding #13) ──────────────────────────────

  /**
   * The refusing rule's own token, when the server sent one. `null` when the response carried no
   * token — either the rule has none or the endpoint has no code channel.
   */
  refusalCode(err: unknown): string | null {
    if (!(err instanceof HttpErrorResponse)) return null;
    const body = err.error as RefusalEnvelope | null | undefined;
    const code = body?.data?.errorCode;
    return typeof code === 'string' && code.trim() !== '' ? code.trim() : null;
  }

  /**
   * True when a supervisor could still get this refusal through, so the caller should offer the
   * step-up. **Never reads the message text.**
   *
   * Three cases, in order:
   *  1. The server named the refusing rule — branch on it. `DISCOUNT_ABOVE_CEILING` (the BLOCK
   *     stance) deliberately returns false: nobody may authorise it, and prompting for an approval
   *     that would be refused anyway is a lie told to a queue of customers.
   *  2. The endpoint speaks codes (`data.code` is present — the POS sale envelope) but named no
   *     rule: the refusal was something else entirely, so do not offer a discount remedy.
   *  3. The endpoint has no code channel at all (e.g. the plain 409 from add-line): fall back to
   *     "was this a business refusal?" — 409/422. That only ADDS an affordance, and the caller is
   *     expected to require an actual discount on the line before showing it.
   */
  approvalMayRescue(err: unknown): boolean {
    const code = this.refusalCode(err);
    if (code !== null) {
      return code === DISCOUNT_APPROVAL_REQUIRED || code === DISCOUNT_APPROVAL_NOT_ACCEPTED;
    }
    if (!(err instanceof HttpErrorResponse)) return false;
    const body = err.error as RefusalEnvelope | null | undefined;
    if (typeof body?.data?.code === 'string') return false;
    return err.status === 409 || err.status === 422;
  }
}
