/**
 * ApAgeingComponent — creditors ageing across all suppliers (AP-11 / RPT-03 / LBO-14).
 *
 * Covers: loads /ap/statement/ageing/by-supplier for the first company (no supplierUid), renders
 * one row per supplier, and totals per currency (never mixing currencies).
 */
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ApAgeingComponent } from './ap-ageing.component';

describe('ApAgeingComponent', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [ApAgeingComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: SessionStore, useValue: { hasPermission: () => true } },
        { provide: OrganisationService, useValue: { current: () => of({ uid: 'ORG1' }) } },
        { provide: CompanyService, useValue: { list: () => of([{ id: '7', name: 'Co' }]) } },
      ],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    TestBed.resetTestingModule();
  });

  it('loads the all-supplier ageing and totals per currency', () => {
    const fixture = TestBed.createComponent(ApAgeingComponent);
    fixture.detectChanges();

    const req = httpMock.expectOne((r) => r.url.includes('/ap/statement/ageing/by-supplier'));
    expect(req.request.params.get('companyId')).toBe('7');
    expect(req.request.params.has('supplierUid')).toBe(false);
    const row = (id: string, name: string, total: number, ccy: string) => ({
      supplierId: id, supplierUid: 'U' + id, supplierCode: 'S' + id, supplierName: name,
      current: total, days1to30: 0, days31to60: 0, days61to90: 0, days91Plus: 0, total,
      currency: ccy,
    });
    req.flush([row('1', 'Alpha', 100, 'TZS'), row('2', 'Beta', 50, 'TZS'), row('3', 'Gamma', 9, 'USD')]);
    fixture.detectChanges();

    const c = fixture.componentInstance;
    expect(c.rows().length).toBe(3);
    expect(c.totals()).toEqual([
      expect.objectContaining({ currency: 'TZS', suppliers: 2, total: 150 }),
      expect.objectContaining({ currency: 'USD', suppliers: 1, total: 9 }),
    ]);
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Alpha');
    expect(text).toContain('Gamma');
  });
});
