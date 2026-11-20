// Golden tile trees. Each entry is the treeDigest of one tiling (tiles, DZI,
// thumbnail and metadata, see support/tree.ts). Regenerate with
// `npm run goldens:update` and explain any change in the PR (see criteria.md).
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { expect } from '@playwright/test';
import { E2E_ROOT, LIBRARY } from './env';
import type { Dzi, TreeDigest } from './tree';

const GOLDENS = path.join(E2E_ROOT, 'goldens', 'goldens.json');
// Trees that differ when the build is linked with mission-image-stub: mission products
// keep the same tiles but are recorded as product type EDR, and overlay products are
// tiled as plain images. Everything else is shared with goldens.json.
const STUB_GOLDENS = path.join(E2E_ROOT, 'goldens', 'goldens.stub.json');
const updating = process.env.UPDATE_GOLDENS === '1';

type Golden = TreeDigest & Pick<Dzi, 'width' | 'height' | 'tileSize' | 'format'> & {
  observed: { date: string; os: string; java: string };
};

function read(file: string): Record<string, Golden> {
  return existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
}

function load(): Record<string, Golden> {
  return LIBRARY === 'stub' ? { ...read(GOLDENS), ...read(STUB_GOLDENS) } : read(GOLDENS);
}

export function expectGolden(key: string, digest: TreeDigest, dzi: Dzi) {
  const goldens = updating ? {} : load();
  const shape = { width: dzi.width, height: dzi.height, tileSize: dzi.tileSize, format: dzi.format };
  if (updating) {
    // with the stub, record only the trees that differ from the shared goldens
    const file = LIBRARY === 'stub' ? STUB_GOLDENS : GOLDENS;
    const target = read(file);
    if (LIBRARY === 'stub' && read(GOLDENS)[key]?.sha256 === digest.sha256) {
      delete target[key];
    } else {
      target[key] = {
        ...shape, ...digest,
        observed: { date: new Date().toISOString().slice(0, 10), os: `${os.platform()}-${os.arch()}`,
          java: process.env.JAVA_VERSION_LABEL ?? 'unknown' },
      };
    }
    const sorted = Object.fromEntries(Object.entries(target).sort(([a], [b]) => a.localeCompare(b)));
    writeFileSync(file, JSON.stringify(sorted, null, 2) + '\n');
    return;
  }
  const g = goldens[key];
  expect(g, `no golden '${key}'; run npm run goldens:update and commit goldens/`).toBeTruthy();
  expect(shape, `DZI of ${key}`).toEqual({ width: g.width, height: g.height, tileSize: g.tileSize, format: g.format });
  expect({ files: digest.files, tiles: digest.tiles }, `file counts of ${key}`).toEqual({ files: g.files, tiles: g.tiles });
  expect(digest.sha256, `tree hash of ${key}`).toBe(g.sha256);
}
