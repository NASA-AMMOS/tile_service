// Gamma handling: what each gammaMode does to the pixels and declares in the tiles.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { VICAR, value } from '../support/synthetic';
import { decode, levels, pixel } from '../support/tree';

const S = VICAR['byte_ramp.vic'];
const toSrgb = (u: number) => (u <= 0.0031308 ? 12.92 * u : 1.055 * u ** (1 / 2.4) - 0.055);
const toLinear = (u: number) => (u <= 0.04045 ? u / 12.92 : ((u + 0.055) / 1.055) ** 2.4);

const MODES: { mode: string; md: Record<string, unknown>; gAMA: boolean; f: (u: number) => number; what: string }[] = [
  { mode: 'none', md: { linear_to_srgb: false, srgb_to_linear: false, tile_header_gamma: 'srgb', tile_data_gamma: 'linear' }, gAMA: false, f: (u) => u, what: 'leaves the values and declares no gamma' },
  { mode: 'passthrough', md: { linear_to_srgb: false, srgb_to_linear: false, tile_header_gamma: 'linear', tile_data_gamma: 'linear' }, gAMA: true, f: (u) => u, what: 'leaves the values and declares linear gamma (gAMA chunk)' },
  { mode: 'convert', md: { linear_to_srgb: true, srgb_to_linear: false, tile_header_gamma: 'srgb', tile_data_gamma: 'srgb' }, gAMA: false, f: toSrgb, what: 'converts linear data to sRGB (the default)' },
  { mode: 'lineartosrgb', md: { linear_to_srgb: true, srgb_to_linear: false, tile_header_gamma: 'srgb', tile_data_gamma: 'srgb' }, gAMA: false, f: toSrgb, what: 'converts linear data to sRGB' },
  { mode: 'srgbtolinear', md: { linear_to_srgb: false, srgb_to_linear: true, tile_header_gamma: 'linear', tile_data_gamma: 'linear' }, gAMA: true, f: toLinear, what: 'converts sRGB data to linear and declares linear gamma' },
];

test.describe('Gamma handling', feature('gamma'), () => {
  for (const m of MODES) {
    test(`gammaMode=${m.mode} ${m.what}`, about(
      `Tiles the BYTE ramp with \`stretchType=none&gammaMode=${m.mode}\`. Passes when metadata.json reports \`gamma_mode=${m.mode}\` and ${Object.entries(m.md).map(([k, v]) => `\`${k}=${v}\``).join(', ')}, the tile ${m.gAMA ? 'has' : 'has no'} PNG gAMA chunk, and every full-resolution pixel of tile 0_0 is within 1 count of 255 * f(DN / 255) for the mode's transfer function.`,
      '@nominal', '@pixels',
    ), async ({ tiler }) => {
      const q = `image=synthetic/byte_ramp.vic&stretchType=none&gammaMode=${m.mode}`;
      const t = await tiler.tile(q);
      const md = JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8'));
      expect(md.gamma_mode).toBe(m.mode);
      expect(Object.fromEntries(Object.keys(m.md).map((k) => [k, md[k]]))).toEqual(m.md);
      const top = levels(S.ns, S.nl).at(-1)!;
      const d = decode(readFileSync(path.join(t.dir, 'image_files', String(top.level), '0_0.png')));
      expect(d.chunks.includes('gAMA'), 'gAMA chunk').toBe(m.gAMA);
      let worst = 0;
      for (let y = 0; y < d.height; y++) for (let x = 0; x < d.width; x++) {
        worst = Math.max(worst, Math.abs(pixel(d, x, y)[0] - 255 * m.f(value(S, y, x) / 255)));
      }
      expect(worst, 'largest difference from the transfer function').toBeLessThanOrEqual(1);
    });
  }

  test('an unknown gammaMode is refused with 400', about(
    `Requests the BYTE ramp with \`gammaMode=bogus\`. Passes on 400.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/byte_ramp.vic&gammaMode=bogus')).status()).toBe(400);
  });
});
