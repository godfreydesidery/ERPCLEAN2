import { provideHttpClient } from '@angular/common/http';
import {
  HttpTestingController,
  provideHttpClientTesting,
} from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { environment } from '../../../../environments/environment';
import { SessionStore } from '../../../core/auth/session.store';
import { Branch } from '../models/branch.model';
import { BranchListComponent } from './branch-list.component';

describe('BranchListComponent — inline rename', () => {
  let fixture: ComponentFixture<BranchListComponent>;
  let httpMock: HttpTestingController;
  let session: SessionStore;
  const apiBase = environment.apiBaseUrl;
  const branchesUrl = `${apiBase}/branches`;

  const branch: Branch = {
    id: '1',
    uid: 'B1',
    companyId: '1',
    companyUid: 'C1',
    code: 'BR-01',
    name: 'Head Office',
    timeZone: 'Africa/Dar_es_Salaam',
    isDefault: true,
    status: 'ACTIVE',
  };

  async function setup(perms: string[]): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    session = TestBed.inject(SessionStore);
    session.clear();
    session.setPermissions(perms);
    fixture = TestBed.createComponent(BranchListComponent);
    fixture.componentRef.setInput('companyUid', 'C1');
    httpMock = TestBed.inject(HttpTestingController);
    await Promise.resolve(); // flush the constructor's queueMicrotask(load)
    httpMock
      .expectOne((r) => r.url === branchesUrl && r.params.get('companyUid') === 'C1')
      .flush([branch]);
    fixture.detectChanges();
  }

  afterEach(() => {
    httpMock.verify();
    session.clear();
  });

  it('renames a branch via PUT /branches/uid/{uid} (name only) and reloads', async () => {
    await setup(['BRANCH.MANAGE']);
    const c = fixture.componentInstance;
    expect(c.canManage()).toBe(true);

    c.startEdit(branch);
    expect(c.editingUid()).toBe('B1');
    expect(c.editName()).toBe('Head Office');

    c.editName.set('Main Branch');
    c.saveEdit(branch);

    const put = httpMock.expectOne(`${branchesUrl}/uid/B1`);
    expect(put.request.method).toBe('PUT');
    expect(put.request.body).toEqual({ name: 'Main Branch' });
    put.flush({ ...branch, name: 'Main Branch' });

    // Success reloads the list.
    httpMock
      .expectOne((r) => r.url === branchesUrl)
      .flush([{ ...branch, name: 'Main Branch' }]);

    expect(c.editingUid()).toBeNull();
    expect(c.branches()[0].name).toBe('Main Branch');
  });

  it('hides the rename control when the user lacks BRANCH.MANAGE', async () => {
    await setup([]);
    expect(fixture.componentInstance.canManage()).toBe(false);
    const editButtons = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ).filter((b) =>
      ((b as HTMLButtonElement).getAttribute('aria-label') ?? '').startsWith('Edit branch '),
    );
    expect(editButtons.length).toBe(0);
  });

  it('does not call the API when the name is unchanged', async () => {
    await setup(['BRANCH.MANAGE']);
    const c = fixture.componentInstance;
    c.startEdit(branch);
    c.saveEdit(branch); // unchanged
    expect(c.editingUid()).toBeNull();
    // afterEach httpMock.verify() asserts no PUT was issued.
  });
});

describe('BranchListComponent — start a branch from another (ADR-0073)', () => {
  let fixture: ComponentFixture<BranchListComponent>;
  let httpMock: HttpTestingController;
  let session: SessionStore;
  const apiBase = environment.apiBaseUrl;
  const branchesUrl = `${apiBase}/branches`;
  const copyMenuUrl = `${apiBase}/products/branch-menu/copy`;
  const copyRoutingUrl = `${apiBase}/restaurant/category-routes/copy`;

  const mk = (uid: string, name: string, status = 'ACTIVE'): Branch => ({
    id: uid,
    uid,
    companyId: '1',
    companyUid: 'C1',
    code: uid,
    name,
    timeZone: 'Africa/Dar_es_Salaam',
    isDefault: uid === 'DAR',
    status,
  });
  const dar = mk('DAR', 'Dar');
  const kia = mk('KIA', 'KIA');

  async function setup(perms: string[], rows: Branch[] = [dar, kia]): Promise<void> {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    session = TestBed.inject(SessionStore);
    session.clear();
    session.setPermissions(perms);
    fixture = TestBed.createComponent(BranchListComponent);
    fixture.componentRef.setInput('companyUid', 'C1');
    httpMock = TestBed.inject(HttpTestingController);
    await Promise.resolve();
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'GET').flush(rows);
    fixture.detectChanges();
  }

  function copyButtons(): HTMLButtonElement[] {
    return Array.from(fixture.nativeElement.querySelectorAll('button')).filter((b) =>
      ((b as HTMLButtonElement).getAttribute('aria-label') ?? '').startsWith('Copy menu or'),
    ) as HTMLButtonElement[];
  }

  afterEach(() => {
    httpMock.verify();
    session.clear();
  });

  it('creating a branch without a source copies nothing (as before)', async () => {
    await setup(['BRANCH.MANAGE', 'PRODUCT.BRANCH.ASSIGN']);
    const c = fixture.componentInstance;
    c.code.set('ARU');
    c.name.set('Arusha');
    c.create();
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'POST').flush(mk('ARU', 'Arusha'));
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'GET').flush([dar, kia]);
    // afterEach verify(): no copy call was made.
  });

  it('creating a branch with a source copies that branch menu into the new one', async () => {
    await setup(['BRANCH.MANAGE', 'PRODUCT.BRANCH.ASSIGN']);
    const c = fixture.componentInstance;
    c.code.set('ARU');
    c.name.set('Arusha');
    c.startFrom.set('DAR');
    c.create();
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'POST').flush(mk('ARU', 'Arusha'));
    const copy = httpMock.expectOne(copyMenuUrl);
    expect(copy.request.body).toEqual({ sourceBranchUid: 'DAR', targetBranchUid: 'ARU' });
    copy.flush({ itemsNowSold: 31, pricesCopied: 11, alreadySetAtTarget: 0 });
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'GET').flush([dar, kia]);
    expect(c.startFrom()).toBe('');
  });

  it('a failed copy after create keeps the branch and says how to retry', async () => {
    await setup(['BRANCH.MANAGE', 'PRODUCT.BRANCH.ASSIGN']);
    const c = fixture.componentInstance;
    c.code.set('ARU');
    c.name.set('Arusha');
    c.startFrom.set('DAR');
    c.create();
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'POST').flush(mk('ARU', 'Arusha'));
    httpMock
      .expectOne(copyMenuUrl)
      .flush({ errors: ['Not allowed.'] }, { status: 403, statusText: 'Forbidden' });
    httpMock.expectOne((r) => r.url === branchesUrl && r.method === 'GET').flush([dar, kia]);
    expect(c.formError()).toContain('Branch created');
    expect(c.formError()).toContain('Copy from');
  });

  it('copy panel: menu only by default — kitchen routing is never copied unless ticked', async () => {
    await setup(['PRODUCT.BRANCH.ASSIGN', 'RESTAURANT.SETUP.MANAGE']);
    const c = fixture.componentInstance;
    expect(copyButtons().length).toBe(2);
    c.openCopy(kia);
    expect(c.copyMenuChecked()).toBe(true);
    expect(c.copyRoutingChecked()).toBe(false);
    expect(c.copySources().map((b) => b.uid)).toEqual(['DAR']);

    c.copySource.set('DAR');
    c.runCopy();
    httpMock
      .expectOne(copyMenuUrl)
      .flush({ itemsNowSold: 31, pricesCopied: 11, alreadySetAtTarget: 11 });
    // afterEach verify(): no routing call.
    expect(c.copyResult()[0]).toContain('31 item(s) now sold here');
    expect(c.copyResult()[0]).toContain('11 item(s) this branch had already set');
  });

  it('copy panel: routing when ticked names the stations the branch lacks', async () => {
    await setup(['PRODUCT.BRANCH.ASSIGN', 'RESTAURANT.SETUP.MANAGE']);
    const c = fixture.componentInstance;
    c.openCopy(kia);
    c.copySource.set('DAR');
    c.copyMenuChecked.set(false);
    c.copyRoutingChecked.set(true);
    c.runCopy();
    const req = httpMock.expectOne(copyRoutingUrl);
    expect(req.request.body).toEqual({ sourceBranchUid: 'DAR', targetBranchUid: 'KIA' });
    req.flush({
      routesCopied: 12,
      alreadyRouted: 0,
      skippedNoMatchingStation: 13,
      stationsMissingAtTarget: ['KITCHEN'],
    });
    expect(c.copyResult()[0]).toContain('no KITCHEN station');
    expect(c.copyResult()[0]).toContain('still print at its default station');
  });

  it('copy panel asks for a source and something to copy before calling the API', async () => {
    await setup(['PRODUCT.BRANCH.ASSIGN']);
    const c = fixture.componentInstance;
    c.openCopy(kia);
    c.runCopy();
    expect(c.copyError()).toContain('Choose the branch');
    c.copySource.set('DAR');
    c.copyMenuChecked.set(false);
    c.runCopy();
    expect(c.copyError()).toContain('Tick what to copy');
  });

  it('shows no copy controls without either permission', async () => {
    await setup(['BRANCH.MANAGE']);
    expect(copyButtons().length).toBe(0);
    expect(fixture.nativeElement.querySelector('#startFrom')).toBeNull();
  });
});
