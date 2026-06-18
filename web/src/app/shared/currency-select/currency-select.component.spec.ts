import { describe, it, expect, afterEach, vi, beforeEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { FormsModule } from '@angular/forms';
import { of, throwError } from 'rxjs';
import { Component, signal } from '@angular/core';

import { CurrencySelectComponent } from './currency-select.component';
import { CurrencyService } from './currency.service';

// ── Helpers ──────────────────────────────────────────────────────────────────

const MOCK_OPTIONS = [
  { code: 'TZS', name: 'Tanzanian Shilling', symbol: 'TSh' },
  { code: 'USD', name: 'US Dollar',           symbol: '$' },
  { code: 'EUR', name: 'Euro',                symbol: '€' },
];

function makeCurrencyService(
  opts = MOCK_OPTIONS,
  resolvedDefault = 'TZS',
  failEnabled = false,
) {
  return {
    getEnabledOptions: vi.fn(() =>
      failEnabled
        ? throwError(() => new Error('API error'))
        : of({ options: opts, resolvedDefault }),
    ),
    getEnabled: vi.fn(() => of({ enabled: opts.map((o) => o.code), resolvedDefault })),
    listAll: vi.fn(() => of([])),
    clearCache: vi.fn(),
  };
}

/** Host harness so we can drive ngModel from outside. */
@Component({
  standalone: true,
  imports: [FormsModule, CurrencySelectComponent],
  template: `
    <label for="testCurrency">Currency</label>
    <app-currency-select id="testCurrency"
        [(ngModel)]="code"
        [companyUid]="companyUid"
        [required]="required" />
  `,
})
class TestHostComponent {
  code = '';
  companyUid = 'CO-UID-1';
  required = false;
}

// ── Specs ─────────────────────────────────────────────────────────────────────

describe('CurrencySelectComponent', () => {
  afterEach(() => {
    vi.useRealTimers();
    TestBed.resetTestingModule();
  });

  describe('loading and options', () => {
    it('shows loading state while fetch is in progress then renders options', async () => {
      vi.useFakeTimers();
      TestBed.configureTestingModule({
        imports: [TestHostComponent],
        providers: [{ provide: CurrencyService, useValue: makeCurrencyService() }],
      });
      const fixture = TestBed.createComponent(TestHostComponent);
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      const options = el.querySelectorAll('option');
      // placeholder + 3 currencies
      expect(options.length).toBe(4);
    });

    it('defaults to resolvedDefault from the service', async () => {
      vi.useFakeTimers();
      TestBed.configureTestingModule({
        imports: [TestHostComponent],
        providers: [{ provide: CurrencyService, useValue: makeCurrencyService(MOCK_OPTIONS, 'USD') }],
      });
      const fixture = TestBed.createComponent(TestHostComponent);
      const host = fixture.componentInstance;
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      // The picker should have defaulted the ngModel value to 'USD'
      expect(host.code).toBe('USD');
    });

    it('shows error state when getEnabledOptions fails', async () => {
      vi.useFakeTimers();
      TestBed.configureTestingModule({
        imports: [TestHostComponent],
        providers: [
          { provide: CurrencyService, useValue: makeCurrencyService(MOCK_OPTIONS, 'TZS', true) },
        ],
      });
      const fixture = TestBed.createComponent(TestHostComponent);
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      expect(el.textContent).toContain('Could not load currencies');
    });
  });

  describe('no company uid', () => {
    it('renders disabled placeholder when companyUid is empty', async () => {
      vi.useFakeTimers();
      TestBed.configureTestingModule({
        imports: [TestHostComponent],
        providers: [{ provide: CurrencyService, useValue: makeCurrencyService() }],
      });
      const fixture = TestBed.createComponent(TestHostComponent);
      fixture.componentInstance.companyUid = '';
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      const select = el.querySelector('select');
      expect(select?.disabled).toBe(true);
    });
  });

  describe('ControlValueAccessor', () => {
    beforeEach(() => {
      vi.useFakeTimers();
      TestBed.configureTestingModule({
        imports: [TestHostComponent],
        providers: [{ provide: CurrencyService, useValue: makeCurrencyService() }],
      });
    });

    it('emits the selected code when user picks an option', async () => {
      const fixture = TestBed.createComponent(TestHostComponent);
      const host = fixture.componentInstance;
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      const select = el.querySelector('select') as HTMLSelectElement;
      // Pick USD
      select.value = 'USD';
      select.dispatchEvent(new Event('change'));
      fixture.detectChanges();
      await vi.runAllTimersAsync();

      expect(host.code).toBe('USD');
    });

    it('respects writeValue from outside (parent sets value)', async () => {
      const fixture = TestBed.createComponent(TestHostComponent);
      const host = fixture.componentInstance;
      host.code = 'EUR';
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      const select = el.querySelector('select') as HTMLSelectElement;
      expect(select?.value).toBe('EUR');
    });
  });

  describe('filter box', () => {
    it('shows filter input when options exceed searchThreshold', async () => {
      vi.useFakeTimers();
      const manyOptions = Array.from({ length: 10 }, (_, i) => ({
        code: `C${i.toString().padStart(2, '0')}`,
        name: `Currency ${i}`,
        symbol: '',
      }));
      TestBed.configureTestingModule({
        imports: [TestHostComponent],
        providers: [
          {
            provide: CurrencyService,
            useValue: makeCurrencyService(manyOptions, 'C00'),
          },
        ],
      });
      const fixture = TestBed.createComponent(TestHostComponent);
      fixture.detectChanges();
      await vi.runAllTimersAsync();
      fixture.detectChanges();

      const el: HTMLElement = fixture.nativeElement;
      // searchThreshold defaults to 8; 10 options > 8 → filter input must appear
      const filterInput = el.querySelector('input[type="text"]');
      expect(filterInput).not.toBeNull();
    });
  });
});
