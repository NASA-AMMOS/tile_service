// Contrast stretch: the limits recorded in metadata.json and the 8-bit values
// in the tiles, computed from the synthetic HALF ramp's known distribution.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { asset } from '../support/data';
import { cliTile } from '../support/run';
import { VICAR, extrema, freshCopy, value } from '../support/synthetic';
import { decode, levels, pixel } from '../support/tree';

const S = VICAR['half_ramp.vic'];
const [MIN, MAX] = extrema(S);

/** The DN below which `percent`% of the pixels lie, and the one above which they lie, as the tiler counts them. */
function percentLimits(percent: number): [number, number] {
  const v: number[] = [];
  for (let l = 0; l < S.nl; l++) for (let x = 0; x < S.ns; x++) v.push(value(S, l, x));
  v.sort((a, b) => a - b);
  const k = Math.floor((v.length * percent) / 100);
  return [v[k], v[v.length - 1 - k]];
}

const metadata = async (tiler: import('../support/server').Tiler, query: string) => {
  await tiler.tile(query);
  const r = await tiler.get(`?${query}&metadata=true`);
  expect(r.status()).toBe(200);
  return r.json();
};

/** Every full-resolution pixel of tile 0_0 against floor(clamp((v - lo) / (hi - lo)) * 255). */
function expectLinearStretch(dir: string, lo: number, hi: number) {
  const top = levels(S.ns, S.nl).at(-1)!;
  const d = decode(readFileSync(path.join(dir, 'image_files', String(top.level), '0_0.png')));
  const bad: string[] = [];
  for (let y = 0; y < d.height; y++) for (let x = 0; x < d.width; x++) {
    const v = value(S, y, x);
    const want = Math.floor(Math.min(1, Math.max(0, (v - lo) / (hi - lo))) * 255);
    const [g] = pixel(d, x, y);
    if (g !== want && bad.length < 5) bad.push(`(${x},${y}) DN ${v} -> ${g}, want ${want}`);
  }
  expect(bad).toEqual([]);
}

test.describe('Contrast stretch', feature('stretch'), () => {
  test('the default is a 0.5% histogram-percent stretch', about(
    `Tiles the 300x200 HALF ramp (DN = 5 + 10*line + 3*sample, ${MIN}..${MAX}) with default options. Passes when metadata.json reports \`stretch_type=histogram_percent\` with 0.5% at each end, \`unstretched_min/max\` of ${MIN}/${MAX}, and stretch limits equal to the DNs that cut 0.5% of the pixels at each end of the ramp's distribution.`,
    '@nominal',
  ), async ({ tiler }) => {
    const md = await metadata(tiler, 'image=synthetic/half_ramp.vic');
    const [lo, hi] = percentLimits(0.5);
    expect({ type: md.stretch_type, lowPct: md.stretch_low_percent, highPct: md.stretch_high_percent, min: md.unstretched_min, max: md.unstretched_max, low: md.stretch_low, high: md.stretch_high })
      .toEqual({ type: 'histogram_percent', lowPct: 0.5, highPct: 0.5, min: [MIN], max: [MAX], low: lo, high: hi });
  });

  test('stretchType=percent takes the percentages from stretchLow and stretchHigh', about(
    `Tiles the HALF ramp with \`stretchType=percent&stretchLow=1&stretchHigh=1\`. Passes when the limits are the DNs that cut 1% of the pixels at each end, and the variant is cached separately from the default (\`custom: true\`).`,
    '@nominal',
  ), async ({ tiler }) => {
    const md = await metadata(tiler, 'image=synthetic/half_ramp.vic&stretchType=percent&stretchLow=1&stretchHigh=1');
    const [lo, hi] = percentLimits(1);
    expect([md.stretch_low, md.stretch_high, md.custom]).toEqual([lo, hi, true]);
  });

  test('stretchType=extrema maps the given DN range linearly onto 0..255', about(
    `Tiles the HALF ramp with \`stretchType=extrema&stretchLow=100&stretchHigh=1000&gammaMode=none\`. Passes when metadata.json reports those limits and every full-resolution pixel equals floor(clamp((DN - 100) / 900) * 255).`,
    '@nominal', '@pixels',
  ), async ({ tiler }) => {
    const q = 'image=synthetic/half_ramp.vic&stretchType=extrema&stretchLow=100&stretchHigh=1000&gammaMode=none';
    const md = await metadata(tiler, q);
    expect([md.stretch_type, md.stretch_low, md.stretch_high]).toEqual(['extrema', 100, 1000]);
    const t = await tiler.tile(q);
    expectLinearStretch(t.dir, 100, 1000);
  });

  test('extremaStretch=true is the legacy spelling of stretchType=extrema', about(
    `Tiles the HALF ramp with \`extremaStretch=true&stretchLow=100&stretchHigh=1000\` (the form the ASTRIA viewer sends). Passes when metadata.json reports an extrema stretch from 100 to 1000.`,
    '@nominal',
  ), async ({ tiler }) => {
    const md = await metadata(tiler, 'image=synthetic/half_ramp.vic&extremaStretch=true&stretchLow=100&stretchHigh=1000');
    expect([md.stretch_type, md.stretch_low, md.stretch_high]).toEqual(['extrema', 100, 1000]);
  });

  test('stretchType=none maps the representable range onto 0..255', about(
    `Tiles the HALF ramp (12 significant bits) with \`stretchType=none&gammaMode=none\`. Passes when the limits are 0 and 4095 and every full-resolution pixel equals floor(DN / 4095 * 255).`,
    '@nominal', '@pixels',
  ), async ({ tiler }) => {
    const q = 'image=synthetic/half_ramp.vic&stretchType=none&gammaMode=none';
    const md = await metadata(tiler, q);
    expect([md.stretch_type, md.stretch_low, md.stretch_high, md.original_bits]).toEqual(['none', 0, 4095, 12]);
    const t = await tiler.tile(q);
    expectLinearStretch(t.dir, 0, 4095);
  });

  test('stretchType=relative interpolates between the data range and the representable range', about(
    `Tiles the HALF ramp with \`stretchType=relative&stretchLow=0.1&stretchHigh=0.9\`. Passes when the limits lie between the data extremes (${MIN}, ${MAX}) and the representable range (0, 4095): low = ${MIN} * 0.1 and high = ${MAX} + (4095 - ${MAX}) * 0.1, to float precision.`,
    '@nominal',
  ), async ({ tiler }) => {
    const md = await metadata(tiler, 'image=synthetic/half_ramp.vic&stretchType=relative&stretchLow=0.1&stretchHigh=0.9');
    expect(md.stretch_type).toBe('relative');
    expect(md.stretch_low).toBeCloseTo(MIN * 0.1, 3);
    expect(md.stretch_high).toBeCloseTo(MAX + (4095 - MAX) * 0.1, 2);
  });

  test('an unknown stretchType is refused with 400', about(
    `Requests the HALF ramp with \`stretchType=bogus\`. Passes on 400.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/half_ramp.vic&stretchType=bogus')).status()).toBe(400);
  });

  test('TileCLI -st extrema -sl -sh applies the same stretch', about(
    `Tiles the HALF ramp with TileCLI \`-st extrema -sl 100 -sh 1000 -gm none\`. Passes when its metadata.json reports the extrema limits and its full-resolution pixels follow the same linear map as the servlet's.`,
    '@nominal', '@pixels',
  ), async ({}, testInfo) => {
    const t = await cliTile(testInfo, freshCopy('half_ramp.vic', 'cli-extrema').input, ['-st', 'extrema', '-sl', '100', '-sh', '1000', '-gm', 'none']);
    const md = JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8'));
    expect([md.stretch_type, md.stretch_low, md.stretch_high]).toEqual(['extrema', 100, 1000]);
    expectLinearStretch(t.dir, 100, 1000);
  });

  test('a Mastcam-Z product records the requested extrema stretch', about(
    `Tiles the public Mastcam-Z FDR \`m20-zcam-575881\` with \`extremaStretch=true&stretchLow=1000&stretchHigh=3000\`, as the viewer's stretch control does. Passes when metadata.json reports an extrema stretch from 1000 to 3000 over 3 bands of 12-bit data with a 4096-bin histogram per band.`,
    '@nominal', '@mission-data',
  ), async ({ tiler }) => {
    const f = path.basename(asset('m20-zcam-575881'));
    const md = await metadata(tiler, `image=alt/${f}&extremaStretch=true&stretchLow=1000&stretchHigh=3000`);
    expect([md.stretch_type, md.stretch_low, md.stretch_high, md.original_bands, md.original_bits]).toEqual(['extrema', 1000, 3000, 3, 12]);
    expect(md.histogram).toHaveLength(3);
    for (const h of md.histogram) expect(h).toHaveLength(md.histogram_bins);
  });
});
