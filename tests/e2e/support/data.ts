// Access to the pinned public test data. Missing or altered data is a hard
// failure, never a skip: run `npm run data:fast` (or data:full) first.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { DATA_DIR, E2E_ROOT } from './env';

export interface Asset {
  id: string;
  file: string;
  url: string;
  tier: 'fast' | 'full';
  sha256: string;
  bytes: number;
  license: string;
  source: string;
  description: string;
}

let manifest: Asset[] | undefined;
const verified = new Set<string>();

export function manifestAssets(): Asset[] {
  manifest ??= JSON.parse(readFileSync(path.join(E2E_ROOT, 'data', 'manifest.json'), 'utf8')).assets;
  return manifest!;
}

/** Absolute path of a verified asset. Throws if it is not in the manifest, missing, or altered. */
export function asset(id: string): string {
  const a = manifestAssets().find((x) => x.id === id);
  if (!a) throw new Error(`unknown test asset '${id}' (not in data/manifest.json)`);
  const file = path.join(DATA_DIR, a.file);
  if (!verified.has(id)) {
    if (!existsSync(file)) {
      throw new Error(`test asset ${a.file} missing; run: npm run data:${a.tier}`);
    }
    const digest = createHash('sha256').update(readFileSync(file)).digest('hex');
    if (digest !== a.sha256) throw new Error(`test asset ${a.file} sha256 ${digest} != pinned ${a.sha256}`);
    verified.add(id);
  }
  return file;
}
