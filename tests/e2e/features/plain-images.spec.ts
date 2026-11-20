// PNG and JPEG inputs: tiled at their own size with their pixels kept.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { attachPreview } from '../support/run';
import { GRADIENT, rgbAt } from '../support/synthetic';
import { decode, expectCompletePyramid, levels, pixel } from '../support/tree';

/** Full-resolution pixels of tile 0_0 that differ from f(gradient), at most five. */
function gradientErrors(dir: string, f: (v: number) => number): string[] {
  const top = levels(GRADIENT.width, GRADIENT.height).at(-1)!;
  const d = decode(readFileSync(path.join(dir, 'image_files', String(top.level), '0_0.png')));
  const bad: string[] = [];
  for (let y = 0; y < d.height; y++) for (let x = 0; x < d.width; x++) {
    const got = pixel(d, x, y).slice(0, 3).join(',');
    const want = rgbAt(x, y).map(f).join(',');
    if (got !== want && bad.length < 5) bad.push(`(${x},${y}) ${got} != ${want}`);
  }
  return bad;
}

test.describe('PNG and JPEG inputs', feature('plain-images'), () => {
  test('a PNG is tiled at its size with its pixels unchanged', about(
    `Tiles a ${GRADIENT.width}x${GRADIENT.height} RGB gradient PNG (r = x mod 256, g = y mod 256, b = (x + y) mod 256) with \`stretchType=none&gammaMode=none\`. Passes when the DZI has the PNG's size, the pyramid is complete with every tile the right size, and every pixel of the full-resolution tiles equals the gradient.`,
    '@nominal', '@pixels',
  ), async ({ tiler }, testInfo) => {
    const t = await tiler.tile('image=synthetic/gradient.png&stretchType=none&gammaMode=none');
    expect([t.dzi.width, t.dzi.height]).toEqual([GRADIENT.width, GRADIENT.height]);
    expectCompletePyramid(t.dir, { checkSizes: true });
    expect(gradientErrors(t.dir, (v) => v)).toEqual([]);
    await attachPreview(testInfo, t.dir, 'gradient.png', false);
  });

  test('a PNG gets the default histogram-percent stretch', about(
    `Tiles the gradient PNG with default options. Plain images are stretched like VICAR products unless they are user uploads or quicklooks. Passes when metadata.json reports \`stretch_type=histogram_percent\` and every full-resolution pixel equals floor(clamp((v - stretch_low) / (stretch_high - stretch_low)) * 255) for the limits it reports.`,
    '@nominal', '@pixels',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/gradient.png');
    const md = JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8'));
    expect(md.stretch_type).toBe('histogram_percent');
    const [lo, hi] = [md.stretch_low, md.stretch_high];
    expect(lo).toBeLessThan(hi);
    expect(gradientErrors(t.dir, (v) => Math.floor(Math.min(1, Math.max(0, (v - lo) / (hi - lo))) * 255))).toEqual([]);
  });

  test('a JPEG is tiled at its size with its pixels kept within JPEG error', about(
    `Tiles the same gradient saved as a quality-95 JPEG with \`stretchType=none&gammaMode=none\`. Passes when the DZI has its size, the pyramid is complete, and the mean absolute difference of the full-resolution pixels from the gradient is under 4 counts (the JPEG round trip).`,
    '@nominal', '@pixels',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/gradient.jpg&stretchType=none&gammaMode=none');
    expect([t.dzi.width, t.dzi.height]).toEqual([GRADIENT.width, GRADIENT.height]);
    expectCompletePyramid(t.dir);
    const top = levels(t.dzi.width, t.dzi.height).at(-1)!;
    const d = decode(readFileSync(path.join(t.dir, 'image_files', String(top.level), '0_0.png')));
    let sum = 0; let n = 0;
    for (let y = 0; y < d.height; y++) for (let x = 0; x < d.width; x++) {
      const p = pixel(d, x, y); const w = rgbAt(x, y);
      for (let c = 0; c < 3; c++) { sum += Math.abs(p[c] - w[c]); n++; }
    }
    expect(sum / n).toBeLessThan(4);
  });

  test('an unknown file extension is refused with 400', about(
    `Requests \`image=synthetic/unsupported.xyz\`. Passes on 400 (unsupported input file extension).`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/unsupported.xyz')).status()).toBe(400);
  });
});
