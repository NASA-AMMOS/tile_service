// RDR overlays: stereo RDRs rendered as RGBA tiles with an alpha mask.
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { asset } from '../support/data';
import { about, feature } from '../support/features';
import { attachPreview, cliTile } from '../support/run';
import { freshLink } from '../support/synthetic';
import { decode, levels } from '../support/tree';

const XYZ = () => `alt/${path.basename(asset('m20-navcam-xyz'))}`;

/** Alpha values of every full-resolution tile, as a histogram. */
function alphas(dir: string, width: number, height: number) {
  const top = levels(width, height).at(-1)!;
  const tiles = path.join(dir, 'image_files', String(top.level));
  const hist = new Map<number, number>();
  let rgba = true;
  for (const f of readdirSync(tiles)) {
    const d = decode(readFileSync(path.join(tiles, f)));
    rgba &&= d.alpha;
    for (let i = 3; i < d.data.length; i += 4) hist.set(d.data[i], (hist.get(d.data[i]) ?? 0) + 1);
  }
  return { rgba, hist };
}

test.describe('RDR overlays', feature('rdr-overlay'), () => {
  test('an XYZ point cloud becomes an RGBA overlay with missing data masked', about(
    `Tiles the public Navcam XYZ RDR \`m20-navcam-xyz\`. Passes when metadata.json reports \`is_overlay=true\` and \`rdr_type=xyz\`, every full-resolution tile is a PNG with an alpha channel, and the alpha values are only 0 (no data) and the default overlay alpha round(0.8627 * 255) = 220, with both present.`,
    '@nominal', '@mission-data', '@pixels', '@mission-library',
  ), async ({ tiler }, testInfo) => {
    const t = await tiler.tile(`image=${XYZ()}`);
    const md = JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8'));
    expect([md.is_overlay, md.rdr_type]).toEqual([true, 'xyz']);
    const { rgba, hist } = alphas(t.dir, t.dzi.width, t.dzi.height);
    expect(rgba, 'tiles have alpha').toBe(true);
    expect([...hist.keys()].sort((a, b) => a - b)).toEqual([0, 220]);
    await attachPreview(testInfo, t.dir, 'XYZ overlay', true);
  });

  test('overlayAlpha sets the opacity of valid overlay pixels', about(
    `Tiles the XYZ RDR with \`overlayAlpha=1\`. Passes when the result is cached as a variant (\`custom: true\`) and its alpha values are only 0 and 255.`,
    '@nominal', '@mission-data', '@pixels', '@mission-library',
  ), async ({ tiler }) => {
    const t = await tiler.tile(`image=${XYZ()}&overlayAlpha=1`);
    expect(t.cachePath).toContain('/cfg/');
    const { hist } = alphas(t.dir, t.dzi.width, t.dzi.height);
    expect([...hist.keys()].sort((a, b) => a - b)).toEqual([0, 255]);
  });

  test('an overlay preference changes the rendering', about(
    `Tiles the XYZ RDR with and without \`overlayPreference=xyz:contourInterval=[0.1,0.3]\`. Passes when the preference is recorded in metadata.json (\`overlay_preferences\`) and the full-resolution tiles differ from the default rendering while the mask (alpha 0 pixels) stays the same.`,
    '@nominal', '@mission-data', '@mission-library',
  ), async ({ tiler }) => {
    const plain = await tiler.tile(`image=${XYZ()}`);
    const pref = await tiler.tile(`image=${XYZ()}&overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D`);
    const md = JSON.parse(readFileSync(path.join(pref.dir, 'metadata.json'), 'utf8'));
    expect(JSON.stringify(md.overlay_preferences ?? '')).toContain('contourInterval');
    const top = String(levels(plain.dzi.width, plain.dzi.height).at(-1)!.level);
    const changed = readdirSync(path.join(plain.dir, 'image_files', top)).filter((f) =>
      !readFileSync(path.join(plain.dir, 'image_files', top, f)).equals(readFileSync(path.join(pref.dir, 'image_files', top, f))));
    expect(changed.length, 'full-resolution tiles changed by the contours').toBeGreaterThan(0);
    expect(alphas(pref.dir, pref.dzi.width, pref.dzi.height).hist.get(0)).toBe(alphas(plain.dir, plain.dzi.width, plain.dzi.height).hist.get(0));
  });

  test('TileCLI -oa sets the overlay alpha', about(
    `Tiles the XYZ RDR (a private link to it, so no other test shares the cache entry) with TileCLI \`-oa 0.5\`. Passes when the overlay's alpha values are only 0 and floor(0.5 * 255) = 127.`,
    '@nominal', '@mission-data', '@pixels', '@mission-library',
  ), async ({}, testInfo) => {
    const t = await cliTile(testInfo, freshLink(asset('m20-navcam-xyz'), 'cli-oa').input, ['-oa', '0.5']);
    const { hist } = alphas(t.dir, t.dzi.width, t.dzi.height);
    expect([...hist.keys()].sort((a, b) => a - b)).toEqual([0, 127]);
  });
});
