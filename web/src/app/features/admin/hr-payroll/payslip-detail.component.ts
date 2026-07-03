import { Component, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { formatMoney } from '../../../shared/money.util';
import { HrPayrollService } from './hr-payroll.service';
import { PayslipDto } from './models/hr-payroll.model';

/**
 * Individual payslip view — read-only, clean/printable-via-browser (handed to the employee).
 * Route: /admin/hr/payslips/uid/:uid
 */
@Component({
  selector: 'app-payslip-detail',
  imports: [RouterLink],
  templateUrl: './payslip-detail.component.html',
  styleUrl: './payslip-detail.component.scss',
})
export class PayslipDetailComponent {
  private readonly hrService = inject(HrPayrollService);

  readonly uid = input.required<string>();

  readonly payslip = signal<PayslipDto | null>(null);
  readonly state = signal<'loading' | 'idle' | 'error'>('loading');

  /** Coerce + format money with thousand separators (shared util). */
  readonly fmtMoney = formatMoney;

  constructor() {
    queueMicrotask(() => this.load());
  }

  private load(): void {
    this.state.set('loading');
    this.hrService.getPayslipByUid(this.uid()).subscribe({
      next: (p) => {
        this.payslip.set(p);
        this.state.set('idle');
      },
      error: () => this.state.set('error'),
    });
  }
}
