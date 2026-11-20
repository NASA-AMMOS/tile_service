// Feature registry (features.yml) and helpers for known failures.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { test } from '@playwright/test';
import { parse } from 'yaml';
import { E2E_ROOT } from './env';

export interface Feature {
  id: string;
  name: string;
  description: string;
  status: 'active' | 'planned' | 'excluded';
  reason?: string;
  milestone?: string;
  code?: string[];
  params?: string[];
  specs?: string[];
  tickets?: string[];
}

export function features(): Feature[] {
  return parse(readFileSync(path.join(E2E_ROOT, 'features.yml'), 'utf8')).features;
}

/** A feature's report tag: @<id>. */
export const tagOf = (f: Pick<Feature, 'id'>) => `@${f.id}`;

/** Details for test.describe(): the feature tag and the tier tag. */
export function feature(id: string, tier: 'fast' | 'full' = 'fast') {
  const f = features().find((x) => x.id === id);
  if (!f) throw new Error(`feature ${id} is not in features.yml`);
  return { tag: [tagOf(f), `@${tier}`] };
}

/**
 * Scope tags: the kind of testing a test does. Every test carries at least one
 * (enforced by registry.spec.ts); the report and the site count tests by scope.
 */
export const SCOPES = {
  '@nominal': 'Nominal: expected use with valid input.',
  '@off-nominal': 'Off-nominal: unsupported options, missing files or invalid arguments; the service must fail cleanly with the right status.',
  '@malformed-input': 'Malformed input: truncated, corrupt or mislabeled files.',
  '@pixels': 'Pixels: decodes the tiles and checks pixel values against values computed from the input.',
  '@golden': 'Golden: the output tree (tiles, DZI, thumbnail, metadata) matches a recorded hash.',
  '@mission-data': 'Mission data: uses public Mars 2020 products from the PDS Imaging Node.',
  '@mission-library': 'Mission library: needs the real mission image library (product types, finders, RDR overlays); skipped in builds against mission-image-stub.',
  '@security': 'Security: SSRF, path traversal, log forging and reflected content (XSS).',
  '@regression': 'Regression: guards a bug that was fixed (see its issue).',
  '@legacy': 'Legacy: a case from test/auto-test-cli.sh or test/auto-test-servlet.sh.',
  '@browser': 'Browser: loads a page in headless Chromium.',
  '@network': 'Network: the service reads a public URL on the internet.',
  '@suite': 'Suite integrity: checks the test suite itself (registry, data manifest).',
} as const;
export type Scope = keyof typeof SCOPES;

/** The annotation type that holds a test's description. */
export const ABOUT = 'what it checks';

/** The annotation type of needsReview notes. */
export const NEEDS_REVIEW = 'needs review';

/** The annotation type of deferred notes. */
export const DEFERRED = 'deferred';

/**
 * Details for test(): what the test does and what makes it pass, then its
 * scope tags. Write the description as plain text; `backticks` mark code and
 * are rendered as code on the procedures page.
 */
export function about(description: string, ...scopes: Scope[]) {
  return { tag: scopes, annotation: { type: ABOUT, description } };
}

/**
 * Mark the running test as a known failure tied to an issue. The report shows
 * it as "expected to fail"; once the bug is fixed the test turns red so the
 * marker gets removed. Never use it to hide a regression or a fix that has not
 * merged yet: those tests fail, and the report says so.
 */
export function knownFailure(issue: string, why: string) {
  test.info().annotations.push({ type: 'issue', description: `${issue}: ${why}` });
  test.fail(true, `${issue}: ${why}`);
}

/**
 * Mark behaviour the suite found that looks wrong but has no ticket yet. The
 * test fails as normal and the report flags it "needs review" until someone
 * decides whether it is a bug (ticket it) or intended (correct the test).
 *
 * With { expectFail: true } (behaviour reproduced on main, not caused by the
 * change under test) the test is also expected to fail, like knownFailure: the
 * run stays green, the report lists it under known issues as needing review,
 * and it turns red once the behaviour changes.
 */
export function needsReview(id: string, why: string, opts: { expectFail?: boolean } = {}) {
  test.info().annotations.push({ type: NEEDS_REVIEW, description: `${id}: ${why}` });
  if (opts.expectFail) {
    test.info().annotations.push({ type: 'issue', description: `${id}: ${why}` });
    test.fail(true, `needs review: ${id}: ${why}`);
  }
}

/**
 * Mark a reviewed bug or missing capability that we decided not to fix yet.
 * The test is skipped unless TILE_SERVICE_RUN_DEFERRED=1; the reason shows in
 * the report.
 */
export function deferred(id: string, why: string) {
  test.info().annotations.push({ type: DEFERRED, description: `${id}: ${why}` });
  test.skip(!process.env.TILE_SERVICE_RUN_DEFERRED, `${id}: ${why}`);
}

/** Link a test to an issue or pull request without changing its outcome. */
export function issue(id: string, what: string) {
  test.info().annotations.push({ type: 'issue', description: `${id}: ${what}` });
}
