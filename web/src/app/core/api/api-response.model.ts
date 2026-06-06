/**
 * Mirrors the backend `ApiResponse<T>` envelope (PROJECT-CONVENTIONS §3.1). Feature code never
 * sees this type — the {@link apiResponseInterceptor} unwraps it to the raw `T` before it reaches
 * services. It exists only so the interceptor can type the wire shape.
 *
 * Paginated endpoints carry a {@link PageMeta} in the `meta` field. Callers that need it must
 * set the {@code SKIP_UNWRAP} context token to bypass the interceptor and receive this full
 * envelope.
 */
export interface ApiResponse<T> {
  data: T | null;
  errors: string[];
  meta?: PageMeta | null;
}

/**
 * Pagination metadata returned by list endpoints that support server-side paging.
 * Mirrors the backend `PageMeta` DTO.
 */
export interface PageMeta {
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}
