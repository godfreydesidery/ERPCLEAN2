import { HttpErrorResponse } from '@angular/common/http';

/** Prefer the server's own sentence: it says what to do about the refusal (e.g. a branch filter). */
export function serverMessage(err: unknown): string | null {
  if (err instanceof HttpErrorResponse) {
    const errors = (err.error as { errors?: string[] })?.errors;
    if (errors?.length) return errors[0];
  }
  return null;
}
