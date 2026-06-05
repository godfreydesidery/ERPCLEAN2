/**
 * Mirrors the backend `ApiResponse<T>` envelope (PROJECT-CONVENTIONS §3.1). Feature code never
 * sees this type — the {@link apiResponseInterceptor} unwraps it to the raw `T` before it reaches
 * services. It exists only so the interceptor can type the wire shape.
 */
export interface ApiResponse<T> {
  data: T | null;
  errors: string[];
  meta?: unknown;
}
