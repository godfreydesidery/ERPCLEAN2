/**
 * PriceListListComponent — the DEFAULT price list control.
 *
 * The default flag is what the Product List and Stock Value reports read selling prices from; with
 * none set, both reports show a blank Selling Price column and tell the user to come here. These
 * specs pin the control that makes that instruction true:
 *
 *  1. The current default is visible at a glance (a badge, not a guess) — and never on an archived
 *     list, which prices nothing whatever its flag says.
 *  2. "Set default" is ONE call to the purpose-built endpoint, which promotes and demotes in a
 *     single transaction; the client never issues a second, generic write.
 *  3. The button is absent from the row that is already default, and from a read-only session.
 *  4. The "no default set" banner appears exactly when NOTHING resolves as the default — matching
 *     the server's own three arms (flag → a DEFAULT/STANDARD code → the only ACTIVE list), so this
 *     screen cannot contradict the reports it is warning about.
 *  5. Create can flag the new list as the default, and leaves the demotion to the server.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of } from 'rxjs';
import { SessionStore } from '../../../core/auth/session.store';
import { AlertService } from '../../../core/feedback/alert.service';
import { PriceListDto } from '../models/product.model';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { PriceListListComponent } from './price-list-list.component';
import { ProductService } from './product.service';

const ORG = { uid: 'ORG1', id: '1', name: 'Acme' };
const CO = { uid: 'CO1', id: '10', name: 'Main Co' };

function priceList(over: Partial<PriceListDto> = {}): PriceListDto {
  return {
    id: '1', uid: 'PL1', companyId: '10', code: 'RETAIL', name: 'Retail',
    priceIncludesVat: true, isDefault: false, status: 'ACTIVE', version: null,
    createdAt: null, createdBy: null, updatedAt: null, updatedBy: null,
    ...over,
  };
}

const DEFAULT_LIST = priceList({ id: '1', uid: 'PL1', code: 'RETAIL', name: 'Retail', isDefault: true });
const OTHER_LIST = priceList({ id: '2', uid: 'PL2', code: 'WHOLE', name: 'Wholesale' });
/** A third unflagged ACTIVE list: with two of them, nothing resolves as the default. */
const THIRD_LIST = priceList({ id: '3', uid: 'PL3', code: 'STAFF', name: 'Staff' });

function makeBed(opts: { rows?: PriceListDto[]; canManage?: boolean } = {}) {
  const rows = opts.rows ?? [DEFAULT_LIST, OTHER_LIST];
  const listSpy = vi.fn(() => of(rows));
  const updateSpy = vi.fn((uid: string) => of(priceList({ uid })));
  const setDefaultSpy = vi.fn((uid: string) => of(priceList({ uid, isDefault: true })));
  const createSpy = vi.fn(() => of(OTHER_LIST));
  const alerts = { success: vi.fn(), error: vi.fn() };

  TestBed.configureTestingModule({
    imports: [PriceListListComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(),
      {
        provide: ProductService,
        useValue: {
          listPriceLists: listSpy,
          createPriceList: createSpy,
          updatePriceList: updateSpy,
          setDefaultPriceList: setDefaultSpy,
          archivePriceList: vi.fn(() => of(OTHER_LIST)),
          restorePriceList: vi.fn(() => of(OTHER_LIST)),
        },
      },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of(ORG)) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([CO])) } },
      { provide: AlertService, useValue: alerts },
      {
        provide: SessionStore,
        useValue: {
          hasPermission: vi.fn(() => opts.canManage ?? true),
          isAuthenticated: signal(true),
          user: signal(null),
          permissions: signal([]),
          activeBranchUid: signal(null),
        },
      },
    ],
  });

  return { listSpy, updateSpy, setDefaultSpy, createSpy, alerts };
}

describe('PriceListListComponent — default price list', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('shows which list is the default, at a glance', () => {
    makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    const bodyRows: HTMLTableRowElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('table.erp-table tbody tr'),
    );
    expect(bodyRows).toHaveLength(2);
    expect(bodyRows[0].textContent).toContain('Default');
    expect(bodyRows[1].textContent).not.toContain('Default');
  });

  it('does not badge an archived list as the default', () => {
    makeBed({ rows: [priceList({ uid: 'PL9', name: 'Old', isDefault: true, status: 'ARCHIVED' })] });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    // A green Default tag one column from an ARCHIVED tag claims a list is pricing stock; nothing
    // reads an archived list, so the row shows the plain "not the default" dash instead.
    expect(fixture.nativeElement.querySelector('.status-tag--ok')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('ARCHIVED');
  });

  it('promotes with ONE call to the purpose-built endpoint, then reloads', () => {
    const { listSpy, updateSpy, setDefaultSpy } = makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    fixture.componentInstance.setDefault(OTHER_LIST);

    // The server promotes and demotes in one transaction and audits it as setting a default.
    expect(setDefaultSpy).toHaveBeenCalledOnce();
    expect(setDefaultSpy).toHaveBeenCalledWith('PL2');
    // A second, generic write would blank fields on a record nobody opened and log a rename.
    expect(updateSpy).not.toHaveBeenCalled();
    // Two rows changed, so the whole list is refetched rather than one row patched.
    expect(listSpy).toHaveBeenCalledTimes(2);
    expect(fixture.componentInstance.settingDefaultUid()).toBeNull();
  });

  it('still issues just the one call when there was no previous default', () => {
    const { updateSpy, setDefaultSpy } = makeBed({ rows: [OTHER_LIST, THIRD_LIST] });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    fixture.componentInstance.setDefault(OTHER_LIST);
    expect(setDefaultSpy).toHaveBeenCalledOnce();
    expect(setDefaultSpy).toHaveBeenCalledWith('PL2');
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('ignores a set-default on the list that is already default', () => {
    const { updateSpy, setDefaultSpy } = makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    fixture.componentInstance.setDefault(DEFAULT_LIST);
    expect(setDefaultSpy).not.toHaveBeenCalled();
    expect(updateSpy).not.toHaveBeenCalled();
  });

  it('offers Set default only on rows that are not already the default', () => {
    makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    const buttons: HTMLButtonElement[] = Array.from(
      fixture.nativeElement.querySelectorAll('button'),
    ).filter((b) => (b as HTMLButtonElement).textContent?.includes('Set default')) as HTMLButtonElement[];
    expect(buttons).toHaveLength(1);
    expect(buttons[0].getAttribute('aria-label')).toBe(
      'Set Wholesale as the default price list',
    );
  });

  it('hides Set default from a session without PRICELIST.MANAGE', () => {
    makeBed({ canManage: false });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.canManage()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('Set default');
  });

  it('says so when nothing resolves as the default — the state that blanks the reports', () => {
    // Two ACTIVE lists, neither flagged and neither coded DEFAULT/STANDARD: the server picks none.
    makeBed({ rows: [OTHER_LIST, THIRD_LIST] });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.resolvedDefault()).toBeNull();
    expect(fixture.componentInstance.hasNoDefault()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('cannot show selling prices');
  });

  it('stays quiet when a default is already set', () => {
    makeBed();
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasNoDefault()).toBe(false);
    expect(fixture.componentInstance.hasManyDefaults()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('cannot show selling prices');
  });

  it('stays quiet for a shop with one unflagged list — the reports price from it', () => {
    // The normal case: nothing ever SET the flag, and the server falls back to the only ACTIVE
    // list. Warning here would flatly contradict a report that is showing prices perfectly well.
    makeBed({ rows: [OTHER_LIST] });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.resolvedDefault()?.uid).toBe('PL2');
    expect(fixture.componentInstance.hasNoDefault()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('cannot show selling prices');
  });

  it('stays quiet when an unflagged list is coded DEFAULT or STANDARD', () => {
    makeBed({ rows: [priceList({ uid: 'PL4', code: 'Default', name: 'Shop' }), THIRD_LIST] });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.resolvedDefault()?.uid).toBe('PL4');
    expect(fixture.componentInstance.hasNoDefault()).toBe(false);
  });

  it('ignores archived lists when judging the default', () => {
    // A flagged-but-archived list prices nothing, so it can neither suppress the warning...
    makeBed({
      rows: [
        priceList({ uid: 'PL9', code: 'OLD', name: 'Old', isDefault: true, status: 'ARCHIVED' }),
        OTHER_LIST,
        THIRD_LIST,
      ],
    });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasNoDefault()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('cannot show selling prices');
  });

  it('flags a duplicate default rather than letting the reports pick one quietly', () => {
    makeBed({ rows: [DEFAULT_LIST, priceList({ uid: 'PL2', name: 'Wholesale', isDefault: true })] });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasManyDefaults()).toBe(true);
    expect(fixture.nativeElement.textContent).toContain('More than one active list is marked');
  });

  it('does not call an archived flagged list a duplicate default', () => {
    // ...nor can it manufacture a clash with the live one.
    makeBed({
      rows: [
        DEFAULT_LIST,
        priceList({ uid: 'PL9', code: 'OLD', name: 'Old', isDefault: true, status: 'ARCHIVED' }),
      ],
    });
    const fixture = TestBed.createComponent(PriceListListComponent);
    fixture.detectChanges();

    expect(fixture.componentInstance.hasManyDefaults()).toBe(false);
    expect(fixture.nativeElement.textContent).not.toContain('More than one active list is marked');
  });

  it('can create a list already flagged as the default, leaving the demotion to the server', () => {
    const { createSpy, updateSpy, setDefaultSpy } = makeBed();
    const comp = TestBed.createComponent(PriceListListComponent).componentInstance;

    comp.newCode.set('WHOLE');
    comp.newName.set('Wholesale');
    comp.newIsDefault.set(true);
    comp.create();

    expect(createSpy).toHaveBeenCalledWith({
      companyUid: 'CO1',
      code: 'WHOLE',
      name: 'Wholesale',
      priceIncludesVat: true,
      isDefault: true,
    });
    // create() already clears the other defaults in the same transaction — a follow-up write would
    // only rewrite a record the admin never opened.
    expect(updateSpy).not.toHaveBeenCalled();
    expect(setDefaultSpy).not.toHaveBeenCalled();
  });

  it('leaves the default flag alone on a plain name edit', () => {
    const { updateSpy } = makeBed();
    const comp = TestBed.createComponent(PriceListListComponent).componentInstance;

    comp.startEdit(DEFAULT_LIST);
    comp.editName.set('Retail 2026');
    comp.saveEdit(DEFAULT_LIST);

    // isDefault omitted ⇒ the backend keeps the stored value; an edit must not be able to
    // un-default a company by accident.
    expect(updateSpy.mock.calls[0]).toEqual([
      'PL1',
      { name: 'Retail 2026', priceIncludesVat: true },
    ]);
  });
});
