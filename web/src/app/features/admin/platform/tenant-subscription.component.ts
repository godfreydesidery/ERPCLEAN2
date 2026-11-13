import { Component, OnInit, WritableSignal, computed, inject, input, signal } from '@angular/core';
import { DatePipe, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { forkJoin } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import {
  BILLING_CYCLES,
  BillingCycle,
  CAP_LABELS,
  CapResource,
  PlanCatalogue,
  PlanCode,
  PlanDefinition,
  RecordPaymentRequest,
  SaveSubscriptionRequest,
  TenantSubscription,
  addCycle,
  localToday,
  stageLabel,
  stageTagClass,
} from './subscription.model';
import { SubscriptionService } from './subscription.service';

const CAP_ORDER: CapResource[] = ['USERS', 'BRANCHES', 'TILLS', 'COMPANIES'];

/**
 * Platform console: one tenant's subscription (ADR-0069). Reading needs ORG.CREATE (the route
 * guard); assigning a plan and recording a payment need ORG.SUSPEND. Server error strings are
 * shown as given.
 */
@Component({
  selector: 'app-tenant-subscription',
  imports: [FormsModule, DatePipe, DecimalPipe, RouterLink],
  templateUrl: './tenant-subscription.component.html',
  styleUrl: './tenant-list.component.scss',
})
export class TenantSubscriptionComponent implements OnInit {
  private readonly service = inject(SubscriptionService);
  private readonly session = inject(SessionStore);

  /** The organisation uid from the route (:uid). */
  readonly uid = input.required<string>();

  readonly state = signal<'loading' | 'ready' | 'error'>('loading');
  readonly subscription = signal<TenantSubscription | null>(null);
  readonly catalogue = signal<PlanCatalogue>({ plans: [], addOns: [] });
  readonly canEdit = computed(() => this.session.hasPermission('ORG.SUSPEND'));

  readonly cycles = BILLING_CYCLES;
  readonly capLabels = CAP_LABELS;
  readonly stageLabel = stageLabel;
  readonly stageTagClass = stageTagClass;

  // ── Plan form ──
  readonly planCode = signal<PlanCode | ''>('');
  readonly addOnRestaurant = signal(false);
  readonly addOnOrbixHq = signal(false);
  readonly billingCycle = signal<BillingCycle>('MONTHLY');
  readonly trialEndsOn = signal('');
  readonly paidUntil = signal('');
  readonly graceDays = signal<number | null>(7);
  readonly readOnlyDays = signal<number | null>(7);
  readonly notes = signal('');
  readonly overrides: Record<CapResource, WritableSignal<number | null>> = {
    USERS: signal<number | null>(null),
    BRANCHES: signal<number | null>(null),
    TILLS: signal<number | null>(null),
    COMPANIES: signal<number | null>(null),
  };
  readonly planSubmitted = signal(false);
  readonly saving = signal(false);
  readonly saveError = signal<string | null>(null);
  readonly saveOk = signal(false);

  // ── Payment form ──
  readonly payAmount = signal<number | null>(null);
  readonly payCurrency = signal('TZS');
  readonly payPaidOn = signal(localToday());
  readonly payPaidUntil = signal('');
  readonly payReference = signal('');
  readonly payNote = signal('');
  readonly paySubmitted = signal(false);
  readonly paying = signal(false);
  readonly payError = signal<string | null>(null);
  readonly payOk = signal(false);

  /** The catalogue entry for the plan chosen in the form (not yet saved), if any. */
  readonly selectedPlan = computed<PlanDefinition | null>(
    () => this.catalogue().plans.find((p) => p.code === this.planCode()) ?? null,
  );

  /** One row per cap: the selected plan's limit beside the override box and the in-use count. */
  readonly capRows = computed(() => {
    const plan = this.selectedPlan();
    const sub = this.subscription();
    return CAP_ORDER.map((resource) => ({
      resource,
      label: CAP_LABELS[resource],
      planLimit: plan ? this.limitOf(plan, resource) : (sub?.caps.find((c) => c.resource === resource)?.planLimit ?? null),
      inUse: sub?.caps.find((c) => c.resource === resource)?.inUse ?? 0,
    }));
  });

  readonly planErrors = computed(() => {
    const errors: string[] = [];
    if (!this.planCode()) errors.push('Choose a plan.');
    if (!this.validDays(this.graceDays())) errors.push('Grace days must be a whole number from 0 to 365.');
    if (!this.validDays(this.readOnlyDays())) errors.push('Read-only days must be a whole number from 0 to 365.');
    for (const r of CAP_ORDER) {
      const v = this.overrides[r]();
      if (v !== null && (!Number.isInteger(v) || v < 0)) {
        errors.push(`${CAP_LABELS[r]} override must be a whole number, 0 or more.`);
      }
    }
    return errors;
  });

  readonly payErrors = computed(() => {
    const errors: string[] = [];
    const amount = this.payAmount();
    if (amount === null || !(amount >= 0)) errors.push('Enter the amount paid.');
    if (!this.payCurrency().trim()) errors.push('Enter the currency.');
    if (!this.payPaidOn()) errors.push('Enter the date paid.');
    if (!this.payPaidUntil()) errors.push('Enter the new paid-until date.');
    return errors;
  });

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.state.set('loading');
    forkJoin({ sub: this.service.get(this.uid()), cat: this.service.catalogue() }).subscribe({
      next: ({ sub, cat }) => {
        this.catalogue.set(cat);
        this.apply(sub);
        this.state.set('ready');
      },
      error: () => this.state.set('error'),
    });
  }

  /** Fills both forms from the server's view of the subscription. */
  private apply(sub: TenantSubscription): void {
    this.subscription.set(sub);
    this.planCode.set(sub.planCode ?? '');
    this.addOnRestaurant.set(sub.addOnRestaurant);
    this.addOnOrbixHq.set(sub.addOnOrbixHq);
    this.billingCycle.set(sub.billingCycle ?? 'MONTHLY');
    this.trialEndsOn.set(sub.trialEndsOn ?? '');
    this.paidUntil.set(sub.paidUntil ?? '');
    this.graceDays.set(sub.assigned ? sub.graceDays : 7);
    this.readOnlyDays.set(sub.assigned ? sub.readOnlyDays : 7);
    this.notes.set(sub.notes ?? '');
    for (const r of CAP_ORDER) {
      this.overrides[r].set(sub.caps.find((c) => c.resource === r)?.override ?? null);
    }
    this.prefillPayment();
  }

  /** Resets the payment form: paid on today, new paid-until = current paid-until (or today) + one cycle. */
  prefillPayment(): void {
    const sub = this.subscription();
    const today = localToday();
    this.payPaidOn.set(today);
    this.payPaidUntil.set(addCycle(sub?.paidUntil || today, sub?.billingCycle ?? null) ?? '');
    this.payAmount.set(null);
    this.payReference.set('');
    this.payNote.set('');
    this.paySubmitted.set(false);
  }

  buildSaveRequest(): SaveSubscriptionRequest {
    const opt = (s: string): string | null => s.trim() || null;
    return {
      planCode: this.planCode() as PlanCode,
      addOnRestaurant: this.addOnRestaurant(),
      addOnOrbixHq: this.addOnOrbixHq(),
      billingCycle: this.billingCycle(),
      trialEndsOn: this.trialEndsOn() || null,
      paidUntil: this.paidUntil() || null,
      graceDays: this.graceDays() ?? 7,
      readOnlyDays: this.readOnlyDays() ?? 7,
      maxUsersOverride: this.overrides.USERS(),
      maxBranchesOverride: this.overrides.BRANCHES(),
      maxTillsOverride: this.overrides.TILLS(),
      maxCompaniesOverride: this.overrides.COMPANIES(),
      notes: opt(this.notes()),
    };
  }

  save(): void {
    this.planSubmitted.set(true);
    this.saveError.set(null);
    this.saveOk.set(false);
    if (this.saving() || this.planErrors().length > 0) return;
    this.saving.set(true);
    this.service.save(this.uid(), this.buildSaveRequest()).subscribe({
      next: (sub) => {
        this.saving.set(false);
        this.planSubmitted.set(false);
        this.apply(sub);
        this.saveOk.set(true);
      },
      error: (err) => {
        this.saving.set(false);
        this.saveError.set(this.messageFrom(err, 'Could not save the subscription.'));
      },
    });
  }

  buildPaymentRequest(): RecordPaymentRequest {
    const opt = (s: string): string | null => s.trim() || null;
    return {
      amount: this.payAmount() ?? 0,
      currencyCode: this.payCurrency().trim().toUpperCase(),
      paidOn: this.payPaidOn(),
      paidUntil: this.payPaidUntil(),
      reference: opt(this.payReference()),
      note: opt(this.payNote()),
    };
  }

  recordPayment(): void {
    this.paySubmitted.set(true);
    this.payError.set(null);
    this.payOk.set(false);
    if (this.paying() || !this.subscription()?.assigned || this.payErrors().length > 0) return;
    this.paying.set(true);
    this.service.recordPayment(this.uid(), this.buildPaymentRequest()).subscribe({
      next: (sub) => {
        this.paying.set(false);
        this.apply(sub);
        this.payOk.set(true);
      },
      error: (err) => {
        this.paying.set(false);
        this.payError.set(this.messageFrom(err, 'Could not record the payment.'));
      },
    });
  }

  /** Number-input helper: '' / null / NaN reads as unset. */
  toNumber(v: unknown): number | null {
    if (v === null || v === undefined || v === '') return null;
    const n = Number(v);
    return Number.isNaN(n) ? null : n;
  }

  private limitOf(plan: PlanDefinition, r: CapResource): number | null {
    switch (r) {
      case 'USERS': return plan.maxUsers;
      case 'BRANCHES': return plan.maxBranches;
      case 'TILLS': return plan.maxTills;
      default: return plan.maxCompanies;
    }
  }

  private validDays(v: number | null): boolean {
    return v !== null && Number.isInteger(v) && v >= 0 && v <= 365;
  }

  private messageFrom(err: unknown, fallback: string): string {
    const errors = (err as { error?: { errors?: string[] } })?.error?.errors;
    return errors?.length ? errors.join(' ') : fallback;
  }
}
