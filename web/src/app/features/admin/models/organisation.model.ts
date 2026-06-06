/** Mirrors the backend OrganisationDto. Numeric id is typed `string` (wire contract). */
export interface Organisation {
  id: string;
  uid: string;
  name: string;
  legalName: string | null;
  defaultTimeZone: string;
  status: string;
}
