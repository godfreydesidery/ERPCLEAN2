/** Mirrors the backend BranchDto. Numeric ids typed `string` (wire contract). */
export interface Branch {
  id: string;
  uid: string;
  companyId: string;
  companyUid: string;
  code: string;
  name: string;
  timeZone: string;
  isDefault: boolean;
  status: string;
}

export interface CreateBranchRequest {
  companyUid: string;
  code: string;
  name: string;
  timeZone?: string;
  makeDefault: boolean;
}

export interface UpdateBranchRequest {
  name: string;
  timeZone?: string;
}

/** POST /products/branch-menu/copy result (ADR-0073): fills gaps only, never overwrites. */
export interface CopyBranchMenuResult {
  itemsNowSold: number;
  pricesCopied: number;
  alreadySetAtTarget: number;
}

/** POST /restaurant/category-routes/copy result: routes only onto stations the target has. */
export interface CopyCategoryRoutesResult {
  routesCopied: number;
  alreadyRouted: number;
  skippedNoMatchingStation: number;
  stationsMissingAtTarget: string[];
}
