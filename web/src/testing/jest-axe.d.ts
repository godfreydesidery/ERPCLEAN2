/**
 * Minimal type declarations for jest-axe v9 (no bundled types, no @types/jest-axe).
 * We only declare what a11y.helper.ts actually uses.
 */
declare module 'jest-axe' {
  import type { AxeResults } from 'axe-core';

  export interface AxeMatchers {
    toHaveNoViolations(): void;
  }

  export interface JestAxeConfigureOptions {
    rules?: Record<string, { enabled: boolean }>;
    [key: string]: unknown;
  }

  /** Wraps axe-core run with configureAxe options applied. */
  export type AxeFunction = (html: Element | string) => Promise<AxeResults>;

  export function configureAxe(options?: JestAxeConfigureOptions): AxeFunction;

  /** Pass to vitest expect.extend() to register toHaveNoViolations. */
  export const toHaveNoViolations: Record<string, (received: AxeResults) => {
    pass: boolean;
    message: () => string;
  }>;
}
