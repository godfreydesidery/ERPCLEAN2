/**
 * Company header block shared by the printable operational reports (Sales Report, Stock Report —
 * SAM Electronix go-live). Mirrors the backend's embedded company summary DTO on both report
 * responses; every field except `name` is nullable — screens render only the non-empty ones.
 */
export interface ReportCompanyHeaderDto {
  name: string;
  legalName: string | null;
  addressLine1: string | null;
  addressLine2: string | null;
  city: string | null;
  region: string | null;
  country: string | null;
  contactPhone: string | null;
  contactEmail: string | null;
  taxId: string | null;
  vrn: string | null;
}

/** Joins the non-empty address fields into a single display line (or '' when all are empty). */
export function formatReportAddress(c: ReportCompanyHeaderDto): string {
  const cityRegion = [c.city, c.region].filter((s) => !!s && s.trim().length > 0).join(', ');
  return [c.addressLine1, c.addressLine2, cityRegion, c.country]
    .filter((s) => !!s && s.trim().length > 0)
    .join(' — ');
}
