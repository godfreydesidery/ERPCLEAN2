import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it } from 'vitest';
import { LookupNoticeComponent, lookupFailure, noAccessMessage } from './lookup-access';

describe('lookup access (ADM-28)', () => {
  it('classifies a 403 as forbidden and anything else as an error', () => {
    expect(lookupFailure(new HttpErrorResponse({ status: 403 }))).toBe('forbidden');
    expect(lookupFailure(new HttpErrorResponse({ status: 500 }))).toBe('error');
    expect(lookupFailure(new Error('boom'))).toBe('error');
  });

  it('renders a no-access sentence for a forbidden lookup, nothing when loaded', () => {
    const fixture = TestBed.createComponent(LookupNoticeComponent);
    fixture.componentRef.setInput('what', 'till');
    fixture.componentRef.setInput('state', 'forbidden');
    fixture.detectChanges();
    const el = fixture.nativeElement as HTMLElement;
    expect(el.textContent).toContain(noAccessMessage('till'));
    expect(el.querySelector('[role="status"]')).not.toBeNull();

    fixture.componentRef.setInput('state', 'idle');
    fixture.detectChanges();
    expect(el.textContent?.trim()).toBe('');
  });

  it('renders a retry hint for a failed lookup', () => {
    const fixture = TestBed.createComponent(LookupNoticeComponent);
    fixture.componentRef.setInput('what', 'price list');
    fixture.componentRef.setInput('state', 'error');
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('could not be loaded');
  });
});
