/** Mirrors the backend OrganisationDto. Numeric id is typed `string` (wire contract). */
export interface Organisation {
  id: string;
  uid: string;
  name: string;
  legalName: string | null;
  defaultTimeZone: string;
  status: string;
  /**
   * The `@alias` half of a composed username (ADR-0062 D-7). Null on an organisation that has none —
   * those installations keep bare usernames, and the create-user form shows no suffix at all.
   */
  alias: string | null;
}
