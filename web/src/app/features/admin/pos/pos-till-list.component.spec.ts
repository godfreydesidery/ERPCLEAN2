import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { AlertService } from '../../../core/feedback/alert.service';
import { SessionStore } from '../../../core/auth/session.store';
import { BranchService } from '../branch/branch.service';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PosTillListComponent } from './pos-till-list.component';

const TILL = { id: '5', uid: 'T1', companyId: '10', branchId: '20', name: 'Till 1', status: 'ACTIVE', offlineAllowed: false };

function setup(canManage: boolean) {
  TestBed.configureTestingModule({
    imports: [PosTillListComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Co' }])) } },
      { provide: BranchService, useValue: { list: vi.fn(() => of([{ uid: 'B1', id: '20', name: 'Main', code: 'M', status: 'ACTIVE' }])) } },
      { provide: AlertService, useValue: { success: vi.fn(), error: vi.fn() } },
      { provide: SessionStore, useValue: {
        hasPermission: vi.fn((p: string) => p === 'POS.TILL.VIEW' || (canManage && p === 'POS.TILL.MANAGE')),
        isAuthenticated: signal(true), user: signal(null), permissions: signal([]), activeBranchUid: signal(null),
      } },
    ],
  });
  const http = TestBed.inject(HttpTestingController);
  const fixture = TestBed.createComponent(PosTillListComponent);
  fixture.detectChanges();
  http.expectOne((r) => r.url.endsWith('/pos/tills') && r.method === 'GET').flush([TILL]);
  fixture.detectChanges();
  return { http, fixture, el: fixture.nativeElement as HTMLElement };
}

describe('PosTillListComponent offline switch', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('PUTs the new offlineAllowed value and updates the row', () => {
    const { http, fixture, el } = setup(true);
    const sw = el.querySelector('input[role="switch"]') as HTMLInputElement;
    expect(sw).toBeTruthy();
    expect(el.textContent).toContain('keeps selling cash, mobile money and card sales');
    sw.click();
    const put = http.expectOne((r) => r.method === 'PUT' && r.url.endsWith('/pos/tills/uid/T1/offline'));
    expect(put.request.body).toEqual({ offlineAllowed: true });
    put.flush({ ...TILL, offlineAllowed: true });
    fixture.detectChanges();
    expect(fixture.componentInstance.rows()[0].offlineAllowed).toBe(true);
    http.verify();
  });

  it('hides the switch without POS.TILL.MANAGE but still shows the state', () => {
    const { http, el } = setup(false);
    expect(el.querySelector('input[role="switch"]')).toBeNull();
    expect(el.textContent).toContain('Offline selling');
    http.verify();
  });
});
