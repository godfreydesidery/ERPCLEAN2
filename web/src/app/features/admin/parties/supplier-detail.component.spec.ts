/**
 * SupplierDetailComponent — AP-10: saving must send the supplier's payment terms, default currency
 * and WHT default back (the old form omitted them and the save wiped them).
 */
import { inputBinding } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { vi } from 'vitest';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { TaxService } from '../tax/tax.service';
import { SupplierDetailComponent } from './supplier-detail.component';
import { SupplierService } from './supplier.service';

describe('SupplierDetailComponent (AP-10)', () => {
  const supplier = {
    id: '3', uid: 'SUP-UID', companyId: '1', code: 'SUP-1', partyType: 'INDIVIDUAL',
    displayName: 'Brewery', legalName: null, tin: null, vatRegistered: false, vrn: null,
    businessRegNo: null, mobileMoneyNo: null, phone: null, email: null, physicalAddress: null,
    postalAddress: null, region: null, district: null, country: 'TZ', supplierKind: 'GOODS',
    paymentTermsDays: 30, paymentTermsId: null, defaultCurrency: 'USD', leadTimeDays: 5,
    minOrderValue: null, defaultWhtTypeId: '8', status: 'ACTIVE', version: '0',
    createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
  };
  const update = vi.fn((_uid: string, _req: unknown) => of(supplier));

  beforeEach(() => {
    update.mockClear();
    TestBed.configureTestingModule({
      imports: [SupplierDetailComponent],
      providers: [
        provideRouter([]),
        { provide: SessionStore, useValue: { hasPermission: () => true } },
        { provide: AlertService, useValue: { success: () => undefined } },
        {
          provide: SupplierService,
          useValue: { getByUid: () => of(supplier), listBranches: () => of([]), update },
        },
        { provide: OrganisationService, useValue: { current: () => of({ uid: 'ORG' }) } },
        { provide: CompanyService, useValue: { list: () => of([]) } },
        { provide: BranchService, useValue: { list: () => of([]) } },
        {
          provide: TaxService,
          useValue: {
            listWhtTypes: () =>
              of([{ id: '8', uid: 'W8', companyId: '1', code: 'WHT5', name: 'Services', kind: 'SERVICES', ratePct: 5, active: true }]),
          },
        },
      ],
    });
  });

  it('sends the existing terms, currency and WHT default on save', async () => {
    const fixture = TestBed.createComponent(SupplierDetailComponent, {
      bindings: [inputBinding('uid', () => 'SUP-UID')],
    });
    fixture.detectChanges();
    await Promise.resolve();
    fixture.detectChanges();

    const c = fixture.componentInstance;
    expect(c.fPaymentTermsDays()).toBe('30');
    c.fPaymentTermsDays.set('45');
    c.save();

    expect(update).toHaveBeenCalledTimes(1);
    const req = update.mock.calls[0][1] as Record<string, unknown>;
    expect(req['paymentTermsDays']).toBe(45);
    expect(req['defaultCurrency']).toBe('USD');
    expect(req['country']).toBe('TZ');
    expect(req['leadTimeDays']).toBe(5);
    expect(req['defaultWhtTypeId']).toBe('8');
  });
});
