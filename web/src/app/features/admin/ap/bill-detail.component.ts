import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, input, OnInit, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { SessionStore } from '../../../core/auth/session.store';
import { SupplierBillDto, SupplierBillStatus } from './models/ap.model';
import { ApService } from './ap.service';

type LoadState = 'loading' | 'idle' | 'error' | 'forbidden';

/**
 * Bill Detail screen.
 * Displays bill header + lines + status + match status.
 * Gated AP.VIEW.
 */
@Component({
  selector: 'app-bill-detail',
  imports: [RouterLink],
  templateUrl: './bill-detail.component.html',
  styleUrl: './bill-detail.component.scss',
})
export class BillDetailComponent implements OnInit {
  private readonly apService = inject(ApService);
  protected readonly session = inject(SessionStore);

  /** Route param :uid — bound via withComponentInputBinding. */
  readonly uid = input<string>('');

  readonly bill = signal<SupplierBillDto | null>(null);
  readonly state = signal<LoadState>('loading');

  readonly canPay = computed(() => this.session.hasPermission('AP.PAYMENT.RUN'));
  readonly canView = computed(() => this.session.hasPermission('AP.VIEW'));

  ngOnInit(): void {
    const u = this.uid();
    if (!u) { this.state.set('error'); return; }
    this.apService.getBill(u).subscribe({
      next: (b) => { this.bill.set(b); this.state.set('idle'); },
      error: (err) =>
        this.state.set(err instanceof HttpErrorResponse && err.status === 403 ? 'forbidden' : 'error'),
    });
  }

  // ── Display helpers ────────────────────────────────────────────────────────

  fmtMoney(v: number | string | null | undefined): string {
    const n = +(v ?? 0);
    return Number.isFinite(n) ? n.toFixed(2) : '0.00';
  }

  statusBadgeClass(status: SupplierBillStatus): string {
    switch (status) {
      case 'DRAFT':          return 'text-bg-secondary';
      case 'MATCHED':        return 'text-bg-info';
      case 'HELD':           return 'text-bg-danger';
      case 'APPROVED':       return 'text-bg-primary';
      case 'PARTIALLY_PAID': return 'text-bg-warning';
      case 'PAID':           return 'text-bg-success';
      default:               return 'text-bg-light border';
    }
  }

  canPayBill(bill: SupplierBillDto): boolean {
    return bill.status === 'MATCHED' || bill.status === 'APPROVED' || bill.status === 'PARTIALLY_PAID';
  }
}
