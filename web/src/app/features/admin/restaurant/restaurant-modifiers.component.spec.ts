/**
 * RestaurantModifiersComponent specs (ADR-0066 D-4): rule wording, validation, the save payload, axe.
 */
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { assertA11y } from '../../../../testing/a11y.helper';
import { CompanyService } from '../company/company.service';
import { OrganisationService } from '../organisation/organisation.service';
import { ProductService } from '../products/product.service';
import { ModifierGroupDto } from './models/restaurant.model';
import { RestaurantModifiersComponent } from './restaurant-modifiers.component';
import { RestaurantService } from './restaurant.service';

const milk: ModifierGroupDto = {
  uid: 'G1', name: 'Milk', minSelect: 1, maxSelect: 1, sortOrder: 0, status: 'ACTIVE',
  options: [
    { uid: 'O1', label: 'Oat milk', productId: null, productUid: null, productName: null, sortOrder: 0, status: 'ACTIVE' },
    { uid: 'O2', label: 'Syrup', productId: '5', productUid: 'P5', productName: 'Syrup extra', sortOrder: 1, status: 'ACTIVE' },
  ],
  products: [{ uid: 'P1', name: 'Latte' }],
};

function makeBed() {
  const restaurant = {
    listModifierGroups: vi.fn(() => of([milk])),
    createModifierGroup: vi.fn(() => of(milk)),
    updateModifierGroup: vi.fn(() => of(milk)),
  };
  TestBed.configureTestingModule({
    imports: [RestaurantModifiersComponent],
    providers: [
      provideHttpClient(), provideHttpClientTesting(), provideRouter([]),
      { provide: RestaurantService, useValue: restaurant },
      { provide: ProductService, useValue: { list: vi.fn(() => of({ rows: [], meta: {} })), getByUid: vi.fn() } },
      { provide: OrganisationService, useValue: { current: vi.fn(() => of({ uid: 'ORG1', id: '1', name: 'Acme' })) } },
      { provide: CompanyService, useValue: { list: vi.fn(() => of([{ uid: 'CO1', id: '10', name: 'Main Co' }])) } },
    ],
  });
  return restaurant;
}

describe('RestaurantModifiersComponent', () => {
  afterEach(() => TestBed.resetTestingModule());

  it('describes rules in plain words and marks priced options', () => {
    makeBed();
    const c = TestBed.createComponent(RestaurantModifiersComponent).componentInstance;
    expect(c.rule(milk)).toBe('Choose 1');
    expect(c.rule({ ...milk, minSelect: 0, maxSelect: null })).toBe('Optional — any number');
    expect(c.optionsText(milk)).toBe('Oat milk, Syrup (priced)');
  });

  it('refuses "at most" below "at least" without calling the API', () => {
    const restaurant = makeBed();
    const c = TestBed.createComponent(RestaurantModifiersComponent).componentInstance;
    c.startNew();
    c.name.set('Milk');
    c.setOption(0, { label: 'Oat' });
    c.minSelect.set(2);
    c.maxSelect.set(1);
    c.save();
    expect(c.error()).toContain('At most');
    expect(restaurant.createModifierGroup).not.toHaveBeenCalled();
  });

  it('saves an edited group with its options in order and its items', () => {
    const restaurant = makeBed();
    const fixture = TestBed.createComponent(RestaurantModifiersComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.edit(milk);
    c.save();
    expect(restaurant.updateModifierGroup).toHaveBeenCalledWith('G1', expect.objectContaining({
      name: 'Milk', minSelect: 1, maxSelect: 1, productUids: ['P1'],
      options: [
        { uid: 'O1', label: 'Oat milk', productUid: null, sortOrder: 0 },
        { uid: 'O2', label: 'Syrup', productUid: 'P5', sortOrder: 1 },
      ],
    }));
  });

  it('has no axe violations with the editor open', async () => {
    makeBed();
    const fixture = TestBed.createComponent(RestaurantModifiersComponent);
    fixture.detectChanges();
    fixture.componentInstance.edit(milk);
    fixture.detectChanges();
    await assertA11y(fixture);
  }, 20_000);
});
