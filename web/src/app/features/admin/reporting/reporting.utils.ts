/**
 * Triggers a browser download from a Blob.
 * Creates an object URL, clicks a temporary anchor, then revokes the URL.
 * Works for PDF, XLSX, and CSV blobs returned by the export endpoints.
 */
export function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  URL.revokeObjectURL(url);
}
