// Background tiling: 202 semantics, progress and error reporting, force.
import { statSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { freshCopy } from '../support/synthetic';

test.describe('Background tiling and progress', feature('async-processing'), () => {
  test('auxiliary requests on an untiled image do not start a tiling', about(
    `Uses a fresh copy of the HALF ramp that was never requested. Passes when \`thumb=true\` and \`metadata=true\` answer 202 (not yet available), \`progress=true\` and \`error=true\` answer 404 (no task), and \`progress=true\` is still 404 afterwards, so none of them started a task.`,
    '@nominal',
  ), async ({ tiler }) => {
    const f = freshCopy('half_ramp.vic', 'aux');
    expect((await tiler.get(`?image=${f.image}&thumb=true`)).status()).toBe(202);
    expect((await tiler.get(`?image=${f.image}&metadata=true`)).status()).toBe(202);
    expect((await tiler.get(`?image=${f.image}&progress=true`)).status()).toBe(404);
    expect((await tiler.get(`?image=${f.image}&error=true`)).status()).toBe(404);
    await new Promise((r) => setTimeout(r, 500));
    expect((await tiler.get(`?image=${f.image}&progress=true`)).status()).toBe(404);
  });

  test('maxWaitSec=0 answers 202 while the tiling runs, then the DZI', about(
    `Requests a fresh copy of the HALF ramp with \`maxWaitSec=0\`. Passes when the first answer is 202 Accepted, \`progress=true\` then reaches 100.00, and the same request then answers 200 with the DZI.`,
    '@nominal',
  ), async ({ tiler }) => {
    const f = freshCopy('half_ramp.vic', 'maxwait');
    expect((await tiler.get(`?image=${f.image}&maxWaitSec=0`)).status()).toBe(202);
    await tiler.waitDone(`image=${f.image}`);
    const p = await tiler.get(`?image=${f.image}&progress=true`);
    expect(await p.text()).toMatch(/^100(\.0+)?\s*$/);
    const r = await tiler.get(`?image=${f.image}&maxWaitSec=0`);
    expect(r.status()).toBe(200);
    expect(await r.text()).toContain('<Image');
  });

  test('a cached image is served without recomputing it', about(
    `Tiles a fresh copy of the BYTE ramp, then requests it again. Passes when the second request answers 200 with the same DZI (size, tiles and cache entry) and metadata.json is not rewritten (same modification time).`,
    '@nominal',
  ), async ({ tiler }) => {
    const f = freshCopy('byte_ramp.vic', 'cached');
    const a = await tiler.tile(`image=${f.image}`);
    const before = statSync(path.join(a.dir, 'metadata.json')).mtimeMs;
    await new Promise((r) => setTimeout(r, 1100));
    const b = await tiler.tile(`image=${f.image}`);
    expect({ ...b.dzi, url: b.cachePath }).toEqual({ ...a.dzi, url: a.cachePath });
    expect(statSync(path.join(b.dir, 'metadata.json')).mtimeMs).toBe(before);
  });

  test('force=true recomputes a cached image', about(
    `Tiles a fresh copy of the BYTE ramp, then requests it with \`force=true\`. Passes when the tiling runs again (metadata.json is rewritten with a later modification time) and the DZI is unchanged.`,
    '@nominal',
  ), async ({ tiler }) => {
    const f = freshCopy('byte_ramp.vic', 'force');
    const a = await tiler.tile(`image=${f.image}`);
    const before = statSync(path.join(a.dir, 'metadata.json')).mtimeMs;
    await new Promise((r) => setTimeout(r, 1100));
    const b = await tiler.tile(`image=${f.image}&force=true`);
    expect({ ...b.dzi, url: b.cachePath }).toEqual({ ...a.dzi, url: a.cachePath });
    // metadata.json is rewritten last, shortly after progress reaches 100
    await expect.poll(() => { try { return statSync(path.join(b.dir, 'metadata.json')).mtimeMs; } catch { return 0; } }, { timeout: 30_000 }).toBeGreaterThan(before);
  });

  test('error=true reports why a tiling failed', about(
    `Requests a fresh copy of the truncated VICAR file, then \`error=true\`. Passes when \`error=true\` answers 200 with a non-empty message while \`progress=true\` does not report success with a cached DZI (\`thumb=true\` is not 200).`,
    '@off-nominal', '@malformed-input',
  ), async ({ tiler }) => {
    const f = freshCopy('truncated.vic', 'error');
    await tiler.get(`?image=${f.image}`);
    let msg = '';
    for (let i = 0; i < 50 && !msg; i++) {
      const e = await tiler.get(`?image=${f.image}&error=true`);
      if (e.status() === 200) msg = (await e.text()).trim(); else await new Promise((r) => setTimeout(r, 200));
    }
    expect(msg).not.toBe('');
    expect((await tiler.get(`?image=${f.image}&thumb=true`)).status()).not.toBe(200);
  });
});
