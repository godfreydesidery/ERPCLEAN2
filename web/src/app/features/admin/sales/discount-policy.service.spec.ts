/**
 * DiscountPolicyService specs (K7).
 *
 * The client mirror of the backend's DiscountPolicyProvider. Two things must hold:
 *  1. It ships OFF, so no tenant sees a change until the persisted policy lands.
 *  2. Its percentage arithmetic matches the server's, on the same basis (unitPrice × quantity),
 *     so an amount-only discount cannot slip past a percentage ceiling and the till never asks for
 *     an approval the server would not have asked for.
 */
import { HttpErrorResponse } from '@angular/common/http';
import {
  DISCOUNT_ABOVE_CEILING,
  DISCOUNT_APPROVAL_NOT_ACCEPTED,
  DISCOUNT_APPROVAL_REQUIRED,
  DiscountPolicy,
  DiscountPolicyService,
} from './discount-policy.service';

function service(): DiscountPolicyService {
  return new DiscountPolicyService();
}

const approveAt10: DiscountPolicy = { action: 'APPROVE', maxDiscountPercent: 10 };

describe('DiscountPolicyService — shipped stance', () => {
  it('is OFF for every company', () => {
    const svc = service();
    expect(svc.policy().action).toBe('OFF');
    expect(svc.isOff()).toBe(true);
  });

  it('never asks for an approval while the policy is OFF, however large the discount', () => {
    const svc = service();
    // 100% off a 1,000 line — the extreme case, still waved through because the policy is off.
    expect(svc.needsApproval(1000, 1000, null)).toBe(false);
  });
});

describe('DiscountPolicyService — effectiveDiscountPercent', () => {
  it('uses an explicit percent verbatim', () => {
    expect(service().effectiveDiscountPercent(1000, null, 25)).toBe(25);
  });

  it('converts an absolute amount against unit price × quantity', () => {
    // 250 off a 1,000 line is 25%.
    expect(service().effectiveDiscountPercent(1000, 250, null)).toBe(25);
  });

  it('reports 100% when an amount is applied to a zero-value line', () => {
    // No meaningful ratio exists, and "give it away" is exactly what a ceiling exists to catch —
    // it must not fall through as "0%, fine".
    expect(service().effectiveDiscountPercent(0, 50, null)).toBe(100);
  });

  it('is zero when there is no discount at all', () => {
    expect(service().effectiveDiscountPercent(1000, null, null)).toBe(0);
    expect(service().effectiveDiscountPercent(1000, 0, 0)).toBe(0);
  });
});

describe('DiscountPolicyService — needsApproval under APPROVE', () => {
  it('allows a discount at or below the ceiling', () => {
    const svc = service();
    expect(svc.needsApproval(1000, 100, null, approveAt10)).toBe(false); // exactly 10%
    expect(svc.needsApproval(1000, 50, null, approveAt10)).toBe(false);  // 5%
  });

  it('requires approval above the ceiling', () => {
    expect(service().needsApproval(1000, 600, null, approveAt10)).toBe(true); // 60%
  });

  it('catches an amount-only discount that exceeds a percentage ceiling', () => {
    // The whole point of converting to a percent on the server's basis.
    expect(service().needsApproval(200, 150, null, approveAt10)).toBe(true); // 75%
  });

  it('reads an unset ceiling as zero, not as unlimited', () => {
    // Turning the policy on must DO something — the negative-stock-toggle failure mode.
    const unconfigured: DiscountPolicy = { action: 'APPROVE', maxDiscountPercent: null };
    expect(service().ceiling(unconfigured)).toBe(0);
    expect(service().needsApproval(1000, 1, null, unconfigured)).toBe(true);
  });
});

describe('DiscountPolicyService — needsApproval under the other stances', () => {
  it('does not prompt under WARN (the discount is allowed; the server just records it)', () => {
    const warn: DiscountPolicy = { action: 'WARN', maxDiscountPercent: 10 };
    expect(service().needsApproval(1000, 600, null, warn)).toBe(false);
  });

  it('does not prompt under BLOCK (no supervisor can unlock it — prompting would be a lie)', () => {
    const block: DiscountPolicy = { action: 'BLOCK', maxDiscountPercent: 10 };
    expect(service().needsApproval(1000, 600, null, block)).toBe(false);
  });
});

/**
 * Reading a refusal (UAT finding #13).
 *
 * The checkout used to decide whether to offer "Ask a supervisor" by searching the server's English
 * sentence for "discount" + "approval". Rewording that sentence — or translating it — removed the
 * button with nothing to show it had, leaving the cashier at a dead end. These specs pin the
 * replacement: branch on the code, never on the prose.
 */
function refusal(status: number, body: unknown): HttpErrorResponse {
  return new HttpErrorResponse({ status, error: body });
}

/** The POS sale envelope: `data.code` is the flow outcome, `data.errorCode` names the rule. */
function posRefusal(errorCode: string | null): HttpErrorResponse {
  return refusal(422, {
    data: { code: 'REJECTED', message: 'anything at all', errorCode },
    errors: ['anything at all'],
  });
}

describe('DiscountPolicyService — reading a server refusal', () => {
  it('offers the step-up when the server says an approval is what is missing', () => {
    expect(service().approvalMayRescue(posRefusal(DISCOUNT_APPROVAL_REQUIRED))).toBe(true);
  });

  it('offers it again when an approval was supplied but not accepted — another supervisor may work', () => {
    expect(service().approvalMayRescue(posRefusal(DISCOUNT_APPROVAL_NOT_ACCEPTED))).toBe(true);
  });

  it('does NOT offer it under BLOCK — no supervisor can unlock that, and prompting would be a lie', () => {
    expect(service().approvalMayRescue(posRefusal(DISCOUNT_ABOVE_CEILING))).toBe(false);
  });

  it('ignores the wording entirely — a refusal that talks about discounts and approvals but names another rule is not offered', () => {
    // The exact case the old prose match got wrong in reverse: this message is full of the trigger
    // words, and the server has told us plainly that the discount policy is not what refused it.
    const decoy = refusal(422, {
      data: {
        code: 'REJECTED',
        message: 'This sale needs approval because the discount period has closed.',
        errorCode: null,
      },
      errors: ['This sale needs approval because the discount period has closed.'],
    });
    expect(service().approvalMayRescue(decoy)).toBe(false);
  });

  it('still offers it on an endpoint with no code channel, when the refusal is a business one', () => {
    // The invoice add-line path answers a plain 409 with no data slot. There is nothing to branch
    // on, so fall back to the HTTP status — an affordance only, and callers additionally require an
    // actual discount on the line before showing it.
    expect(service().approvalMayRescue(refusal(409, { errors: ['Something was refused.'] }))).toBe(true);
    expect(service().approvalMayRescue(refusal(422, { errors: ['Something was refused.'] }))).toBe(true);
  });

  it('never offers it for a failure a supervisor cannot fix', () => {
    const svc = service();
    expect(svc.approvalMayRescue(refusal(403, { errors: ['You do not have permission.'] }))).toBe(false);
    expect(svc.approvalMayRescue(refusal(500, { errors: ['An unexpected error occurred.'] }))).toBe(false);
    expect(svc.approvalMayRescue(new Error('offline'))).toBe(false);
    expect(svc.approvalMayRescue(null)).toBe(false);
  });

  it('reports the raw code so a caller can tell the refusals apart', () => {
    const svc = service();
    expect(svc.refusalCode(posRefusal(DISCOUNT_APPROVAL_REQUIRED))).toBe(DISCOUNT_APPROVAL_REQUIRED);
    expect(svc.refusalCode(posRefusal(null))).toBeNull();
    expect(svc.refusalCode(refusal(409, { errors: ['no data slot'] }))).toBeNull();
  });

  it('the codes match the backend DiscountRefusalCode names exactly', () => {
    // They are the wire contract: rename one on either side and the button silently stops appearing,
    // which is the very failure this replaced.
    expect(DISCOUNT_APPROVAL_REQUIRED).toBe('DISCOUNT_APPROVAL_REQUIRED');
    expect(DISCOUNT_APPROVAL_NOT_ACCEPTED).toBe('DISCOUNT_APPROVAL_NOT_ACCEPTED');
    expect(DISCOUNT_ABOVE_CEILING).toBe('DISCOUNT_ABOVE_CEILING');
  });
});
