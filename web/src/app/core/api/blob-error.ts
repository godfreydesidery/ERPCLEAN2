import { HttpErrorResponse } from '@angular/common/http';

/**
 * Extract a user-safe message from a failed HTTP call.
 *
 * For blob downloads (`responseType: 'blob'`) the error body arrives as a {@link Blob}, so the
 * usual `err.error.errors[]` read sees a Blob and silently falls back to a generic message. This
 * reads the Blob as text, parses the `ApiResponse` envelope, and returns its first user-facing
 * error — so a backend message like "Document templates aren't set up…" actually reaches the user.
 * Non-blob errors are handled too; anything unparseable yields the supplied fallback.
 */
export async function blobErrorMessage(err: unknown, fallback: string): Promise<string> {
  if (err instanceof HttpErrorResponse) {
    if (err.error instanceof Blob) {
      try {
        const parsed = JSON.parse(await err.error.text()) as { errors?: string[] };
        if (parsed.errors?.length) return parsed.errors[0];
      } catch {
        /* body wasn't JSON (e.g. the actual PDF, or empty) — use the fallback */
      }
    } else {
      const errors = (err.error as { errors?: string[] } | null)?.errors;
      if (errors?.length) return errors[0];
    }
  }
  return fallback;
}
