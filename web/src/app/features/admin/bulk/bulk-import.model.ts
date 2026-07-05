/**
 * Bulk / mass data operations — generic Excel import wizard.
 * Mirrors the backend `com.erp.modules.bulk` DTOs (Template download → validate → commit).
 */

/** One importable master-data entity type, scoped to what the current user may import. */
export interface EntityDescriptor {
  readonly key: string;
  readonly label: string;
  readonly permissionCode: string;
}

export type RowAction = 'CREATE' | 'UPDATE' | 'ERROR';

export interface RowOutcome {
  readonly rowNumber: number;
  readonly action: RowAction;
  readonly reference: string | null;
  readonly message: string | null;
}

export type ImportMode = 'VALIDATE' | 'COMMIT';

export interface ImportReport {
  readonly entityKey: string;
  readonly mode: ImportMode;
  readonly total: number;
  readonly created: number;
  readonly updated: number;
  readonly errors: number;
  readonly rows: readonly RowOutcome[];
}
