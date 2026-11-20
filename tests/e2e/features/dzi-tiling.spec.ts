// Deep Zoom tiling of synthetic VICAR images: pyramid structure, exact pixels,
// CLI/servlet agreement and clean failures on bad input.
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature, needsReview } from '../support/features';
import { expectGolden } from '../support/goldens';
import { attachPreview, cliTile, tileCli } from '../support/run';
import { VICAR, freshCopy, value } from '../support/synthetic';
import { decode, expectCompletePyramid, levels, pixel, tileSize, treeDigest } from '../support/tree';

const TYPES = ['byte_ramp.vic', 'half_ramp.vic', 'byte_rgb.vic', 'real_ramp.vic'];

test.describe('Deep Zoom pyramids of synthetic VICAR images', feature('dzi-tiling'), () => {
  for (const name of TYPES) {
    const s = VICAR[name];
    test(`TileCLI tiles a ${s.format} ${s.nb}-band image (${name})`, about(
      `Writes a ${s.ns}x${s.nl}x${s.nb} ${s.format} VICAR ramp and tiles it with TileCLI. Passes when the DZI declares ${s.ns}x${s.nl}, the pyramid has levels 0..ceil(log2(max(width, height))) with every tile present and each tile exactly tile size plus overlap (clipped at the image edge), the thumbnail and metadata are written, and the tree matches the golden.`,
      '@nominal', '@golden',
    ), async ({}, testInfo) => {
      const t = await cliTile(testInfo, `test/synthetic/${name}`);
      expect([t.dzi.width, t.dzi.height]).toEqual([s.ns, s.nl]);
      expectCompletePyramid(t.dir, { checkSizes: true });
      const md = JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8'));
      expect({ width: md.width, height: md.height, bands: md.original_bands, float: md.original_is_float })
        .toEqual({ width: s.ns, height: s.nl, bands: s.nb, float: s.format === 'REAL' });
      expectGolden(`cli/synthetic/${name}`, t.digest, t.dzi);
      await attachPreview(testInfo, t.dir, name, false);
    });
  }

  test('TileCLI tiles a FULL (32-bit integer) image', about(
    `Writes a 96x64 FULL VICAR ramp and tiles it with TileCLI. Passes when the run succeeds and the pyramid is complete. FULL images currently fail with "failed to parse image metadata" because ImageInfo.parseVICAR never sets the sample size for FULL/LONG.`,
    '@nominal', '@regression',
  ), async ({}, testInfo) => {
    needsReview('ImageInfo.java:254', 'FULL/LONG VICAR images are rejected: parseVICAR leaves bytesPerSample at 0 for them', { expectFail: true });
    const t = await cliTile(testInfo, 'test/synthetic/full_ramp.vic');
    expect([t.dzi.width, t.dzi.height]).toEqual([96, 64]);
  });

  test('top-level tiles hold exactly the input pixels', about(
    `Tiles the 520x300 BYTE ramp (pixel = (line + sample) mod 256) through the servlet with \`stretchType=none&gammaMode=none\`, so no value is changed. Decodes every full-resolution tile and passes when each pixel equals the ramp value at its image position, accounting for the 1-pixel tile overlap.`,
    '@nominal', '@pixels',
  ), async ({ tiler }) => {
    const s = VICAR['byte_ramp.vic'];
    const t = await tiler.tile('image=synthetic/byte_ramp.vic&stretchType=none&gammaMode=none');
    const top = levels(s.ns, s.nl).at(-1)!;
    const dir = path.join(t.dir, 'image_files', String(top.level));
    let checked = 0;
    for (const f of readdirSync(dir)) {
      const [c, r] = f.replace('.png', '').split('_').map(Number);
      const d = decode(readFileSync(path.join(dir, f)));
      const box = tileSize(t.dzi, top.width, top.height, c, r);
      expect([d.width, d.height], f).toEqual([box.width, box.height]);
      const bad: string[] = [];
      for (let y = 0; y < d.height; y++) for (let x = 0; x < d.width; x++) {
        const want = value(s, box.y0 + y, box.x0 + x);
        const [g] = pixel(d, x, y);
        if (g !== want && bad.length < 5) bad.push(`(${box.x0 + x},${box.y0 + y}) ${g} != ${want}`);
      }
      expect(bad, `pixels of tile ${f}`).toEqual([]);
      checked += d.width * d.height;
    }
    expect(checked).toBeGreaterThanOrEqual(s.ns * s.nl);
  });

  test('TileCLI and the servlet write the same tiles', about(
    `Tiles the 3-band BYTE ramp once with TileCLI and once through the servlet (fresh copies, default options). Passes when both trees contain the same tile files with identical bytes, and the same thumbnail.`,
    '@nominal',
  ), async ({ tiler }, testInfo) => {
    const a = freshCopy('byte_rgb.vic', 'cli-vs-servlet');
    const cli = await cliTile(testInfo, a.input);
    const srv = await tiler.tile(`image=${a.image}`);
    const files = (dir: string) => {
      const out: Record<string, string> = {};
      const walk = (d: string) => readdirSync(d, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? walk(path.join(d, e.name))
        : out[path.relative(dir, path.join(d, e.name))] = readFileSync(path.join(d, e.name)).toString('base64')));
      walk(path.join(dir, 'image_files'));
      out['thumbnail.png'] = readFileSync(path.join(dir, 'thumbnail.png')).toString('base64');
      return out;
    };
    const c = files(cli.dir); const v = files(srv.dir);
    expect(Object.keys(v).sort()).toEqual(Object.keys(c).sort());
    for (const k of Object.keys(c)) expect(v[k] === c[k], `${k} identical`).toBe(true);
    expect(treeDigest(srv.dir).tiles).toBe(cli.digest.tiles);
  });
});

test.describe('Bad input', feature('dzi-tiling'), () => {
  test('a missing image is 404', about(
    `Requests \`image=synthetic/no_such_file.vic\`. Passes on 404, with no tiling started (\`progress=true\` is also 404).`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/no_such_file.vic')).status()).toBe(404);
    expect((await tiler.get('?image=synthetic/no_such_file.vic&progress=true')).status()).toBe(404);
  });

  test('a text file with an image extension is refused with 400', about(
    `Requests a plain text file named \`not_an_image.vic\`. Passes when the service answers 400 (no reader can decode it), not 200 or 500.`,
    '@malformed-input',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/not_an_image.vic')).status()).toBe(400);
  });

  test('a truncated VICAR file fails with a reported read error', about(
    `Requests a VICAR file cut off after 4000 bytes (its label promises 156000 bytes of pixels). Passes when the DZI request does not return 200, and \`error=true\` reports the read error, so clients can tell a broken product from one still in progress.`,
    '@malformed-input',
  ), async ({ tiler }) => {
    const f = freshCopy('truncated.vic', 'truncated');
    const r = await tiler.get(`?image=${f.image}`);
    expect(r.status(), 'DZI of a truncated file').not.toBe(200);
    expect(r.status()).toBeGreaterThanOrEqual(400);
    let err = '';
    for (let i = 0; i < 50 && !err; i++) {
      const e = await tiler.get(`?image=${f.image}&error=true`);
      if (e.status() === 200) err = await e.text(); else await new Promise((res) => setTimeout(res, 200));
    }
    expect(err, 'error=true reports why').toMatch(/IOException|error|exception/i);
  });

  test('TileCLI exits non-zero on an unreadable input', about(
    `Runs TileCLI on the text file \`not_an_image.vic\`. Passes when it exits with a non-zero code, so scripts and batch jobs can detect the failure, and writes no pyramid. TileCLI currently logs "task error ... no compatible image reader" and exits 0.`,
    '@malformed-input',
  ), async ({}, testInfo) => {
    needsReview('TileCLI.java', 'TileCLI exits 0 when the tiling task fails', { expectFail: true });
    const r = await tileCli(testInfo, ['-dcd', 'tmp', '-i', 'test/synthetic/not_an_image.vic', '-m', 'M20', '-fc', '-cl', 'test/cache']);
    expect(r.exitCode).not.toBe(0);
    expect(`${r.stdout}${r.stderr}`).not.toMatch(/saved \d+ files/);
  });
});
