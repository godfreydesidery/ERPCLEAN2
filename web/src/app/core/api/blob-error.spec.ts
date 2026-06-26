import { HttpErrorResponse } from '@angular/common/http';
import { describe, expect, it } from 'vitest';
import { blobErrorMessage } from './blob-error';

describe('blobErrorMessage', () => {
  const FALLBACK = 'Could not generate the PDF.';

  it('reads the server message out of a Blob error body (blob downloads)', async () => {
    const body = new Blob([JSON.stringify({ errors: ["Document templates aren't set up for this company yet."] })], {
      type: 'application/json',
    });
    const err = new HttpErrorResponse({ error: body, status: 404 });
    expect(await blobErrorMessage(err, FALLBACK)).toBe("Document templates aren't set up for this company yet.");
  });

  it('reads errors[] from a plain (non-blob) JSON error body', async () => {
    const err = new HttpErrorResponse({ error: { errors: ['Plain message'] }, status: 409 });
    expect(await blobErrorMessage(err, FALLBACK)).toBe('Plain message');
  });

  it('falls back when the Blob body is not a JSON error envelope', async () => {
    const err = new HttpErrorResponse({ error: new Blob(['%PDF-1.4 binary'], { type: 'application/pdf' }), status: 500 });
    expect(await blobErrorMessage(err, FALLBACK)).toBe(FALLBACK);
  });

  it('falls back for a non-HTTP error', async () => {
    expect(await blobErrorMessage(new Error('boom'), FALLBACK)).toBe(FALLBACK);
  });
});
