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
