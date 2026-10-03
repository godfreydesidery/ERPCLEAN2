import { describe, expect, it, afterEach } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { TrendChartComponent, TrendSeries } from './trend-chart.component';
import { BarListComponent } from './bar-list.component';
import { assertA11y } from '../../../testing/a11y.helper';

const LABELS = ['P1 2026', 'P2 2026', 'P3 2026'];
const SERIES: TrendSeries[] = [
  { key: 'rev', name: 'Revenue', color: '#2563eb', values: [100000, 150000, 120000], area: true },
  { key: 'net', name: 'Net profit', color: '#eb6834', values: [20000, -5000, 30000] },
];

function mount() {
  TestBed.configureTestingModule({ imports: [TrendChartComponent] });
  const fixture = TestBed.createComponent(TrendChartComponent);
  fixture.componentRef.setInput('labels', LABELS);
  fixture.componentRef.setInput('series', SERIES);
  fixture.componentRef.setInput('label', 'Revenue and net profit');
  fixture.componentRef.setInput('unit', 'TZS');
  fixture.detectChanges();
  return fixture;
}

describe('TrendChartComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('draws one line per series and a legend for two series', () => {
    const fixture = mount();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.querySelectorAll('path[stroke-width="2"]').length).toBe(2);
    expect(el.querySelectorAll('path[fill-opacity="0.1"]').length).toBe(1); // only the lead series is washed
    expect(Array.from(el.querySelectorAll('.tc__legend li')).map((li) => li.textContent?.trim()))
      .toEqual(['Revenue', 'Net profit']);
  });

  it('arrow keys read each period aloud, with every series in one line', () => {
    const fixture = mount();
    const el = fixture.nativeElement as HTMLElement;
    const plot = el.querySelector('.tc__plot') as HTMLElement;
    plot.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight' }));
    fixture.detectChanges();
    expect(el.querySelector('output')?.textContent).toBe('P1 2026: Revenue TZS 100,000.00, Net profit TZS 20,000.00');
    plot.dispatchEvent(new KeyboardEvent('keydown', { key: 'End' }));
    fixture.detectChanges();
    expect(el.querySelector('.tc__tip-title')?.textContent).toBe('P3 2026');
    plot.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(el.querySelector('.tc__tip')).toBeNull();
  });

  it('the table view shows every value without hovering', () => {
    const fixture = mount();
    const el = fixture.nativeElement as HTMLElement;
    (el.querySelector('.tc__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    const cells = Array.from(el.querySelectorAll('tbody td')).map((td) => td.textContent?.trim());
    expect(cells).toEqual(['100,000.00', '20,000.00', '150,000.00', '-5,000.00', '120,000.00', '30,000.00']);
    expect(el.querySelector('thead')?.textContent).toContain('Revenue (TZS)');
  });

  it('has no axe violations in chart or table view', async () => {
    const fixture = mount();
    await assertA11y(fixture);
    (fixture.nativeElement.querySelector('.tc__toggle') as HTMLButtonElement).click();
    fixture.detectChanges();
    await assertA11y(fixture);
  });
});

describe('BarListComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('scales bars to the largest row and prints each value as text', () => {
    TestBed.configureTestingModule({ imports: [BarListComponent] });
    const fixture = TestBed.createComponent(BarListComponent);
    fixture.componentRef.setInput('label', 'Sales by branch');
    fixture.componentRef.setInput('unit', 'TZS');
    fixture.componentRef.setInput('rows', [
      { key: 'a', label: 'HQ — Head Office', sublabel: '8 invoices', value: 250000 },
      { key: 'b', label: 'NBI — Nairobi', sublabel: '4 invoices', value: 125000 },
    ]);
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    const widths = Array.from(el.querySelectorAll('.bl__fill')).map((f) => (f as HTMLElement).style.width);
    expect(widths).toEqual(['100%', '50%']);
    expect(el.querySelector('.bl__value')?.textContent?.trim()).toBe('TZS 250,000.00');
    expect(el.querySelector('ul')?.getAttribute('aria-label')).toBe('Sales by branch');
  });
});
