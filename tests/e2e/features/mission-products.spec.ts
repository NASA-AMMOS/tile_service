// Public Mars 2020 products tiled by TileCLI and by the servlet: the cases of
// test/auto-test-cli.sh and test/auto-test-servlet.sh, checked both against the
// SHA1s those scripts recorded (test/sha1.sh, ported in support/tree.ts) and
// against the suite's own goldens (goldens/goldens.json).
import path from 'node:path';
import { expect, test } from '../support/test';
import { asset } from '../support/data';
import { about, feature } from '../support/features';
import { expectGolden } from '../support/goldens';
import { attachPreview, cliTile, productInput } from '../support/run';
import { LIBRARY } from '../support/env';
import { SRV_DIR } from '../support/server';
import { expectCompletePyramid, legacyTreeSha1, treeDigest } from '../support/tree';
import { CLI_DIR } from '../support/server';

/** The products of test/download-data.sh, their manifest ids and the SHA1s in auto-test-cli.sh / auto-test-servlet.sh. */
const PRODUCTS: { id: string; tier: 'fast' | 'full'; legacy: string; what: string }[] = [
  { id: 'm20-navcam-nlg-fdr', tier: 'fast', legacy: '0790b191c33408a4ae268d678b6077c6a1c079c7', what: 'Navcam FDR' },
  { id: 'm20-navcam-xyz', tier: 'fast', legacy: '945960bfaf532277b0e48013421e9323da2047d2', what: 'Navcam XYZ overlay' },
  { id: 'm20-zcam-575881', tier: 'fast', legacy: '6b07603ef210678713dc78be90358571082663a5', what: 'Mastcam-Z color FDR' },
  { id: 'm20-navcam-mosaic', tier: 'full', legacy: '5d738126f1d796d4aa0d8eb1269a72a72855838f', what: 'Navcam color mosaic' },
  { id: 'm20-zcam-574341', tier: 'full', legacy: '3f380efa9dc6ab116c9e2a593652bec4d736cbb3', what: 'Mastcam-Z color FDR' },
  { id: 'm20-zcam-574647', tier: 'full', legacy: 'd43612e6c512fa742b2330d0e600c3eb4e2c38d9', what: 'Mastcam-Z color FDR' },
  { id: 'm20-zcam-575350', tier: 'full', legacy: '5f34138636aa0254a76518bd62fef09033e7689a', what: 'Mastcam-Z color FDR' },
  { id: 'm20-zcam-mosaic', tier: 'full', legacy: '661a2f948fc17737be4954fc9dde13170e427a36', what: 'Mastcam-Z 486 MB orthomosaic' },
];
const file = (id: string) => path.basename(asset(id));

/**
 * The legacy SHA1s cover metadata.json, which names the product type the real
 * library parses from the file name; with mission-image-stub the tree is checked
 * against goldens/goldens.stub.json only.
 */
function expectLegacy(actual: string, recorded: string) {
  if (LIBRARY === 'mars') expect(actual, 'test/sha1.sh of the cache entry').toBe(recorded);
}

for (const tier of ['fast', 'full'] as const) {
  test.describe(`Mission products through TileCLI (${tier})`, feature('dzi-tiling', tier), () => {
    for (const p of PRODUCTS.filter((x) => x.tier === tier)) {
      test(`TileCLI tiles ${p.id}`, about(
        `Runs TileCLI with the legacy options (\`-dcd tmp -it 0 -zt 0 -d -i test/<file> -m M20 -fc -cl test/cache\`) on the public ${p.what} \`${p.id}\`. Passes when the run exits 0 without errors, the pyramid is complete (every level and tile the DZI implies), the cache entry hashes to the SHA1 recorded in test/auto-test-cli.sh (\`${p.legacy.slice(0, 12)}...\`), and the tree matches the golden.`,
        '@nominal', '@mission-data', '@golden', '@legacy',
      ), async ({}, testInfo) => {
        const t = await cliTile(testInfo, productInput(p.id));
        expectLegacy(legacyTreeSha1(CLI_DIR, t.rel), p.legacy);
        expectGolden(`cli/${p.id}`, t.digest, t.dzi);
        await attachPreview(testInfo, t.dir, file(p.id), true);
      });
    }
  });
}

// Cache entries are shared by variants of the same product (cfg/<hash>), and
// the legacy SHA1s of the variants cover the whole entry, so these run in the
// script's order and no other test tiles these test/<product> paths.
async function legacyServlet(tiler: import('../support/server').Tiler, query: string) {
  const t = await tiler.tile(query);
  expectCompletePyramid(t.dir);
  const nominal = `test/cache/${t.cachePath.split('/cfg/')[0]}`;
  return { ...t, legacy: legacyTreeSha1(SRV_DIR, nominal) };
}

for (const tier of ['fast', 'full'] as const) {
  test.describe.serial(`Mission products through the servlet (${tier})`, feature('dzi-tiling', tier), () => {
    for (const p of PRODUCTS.filter((x) => x.tier === tier)) {
      test(`servlet tiles ${p.id}`, about(
        `Requests \`GET /image_tiler?image=<file>\` for the public ${p.what} \`${p.id}\` and polls \`progress=true\` until it reports 100. Passes when the DZI request returns 200, the pyramid is complete, the cache entry hashes to the SHA1 in test/auto-test-servlet.sh and the tree matches the golden.`,
        '@nominal', '@mission-data', '@golden', '@legacy',
      ), async ({ tiler }, testInfo) => {
        const t = await legacyServlet(tiler, `image=${file(p.id)}`);
        expectLegacy(t.legacy, p.legacy);
        expectGolden(`servlet/${p.id}`, treeDigest(t.dir), t.dzi);
        await attachPreview(testInfo, t.dir, file(p.id), true);
      });
    }

    if (tier === 'fast') {
      test('servlet tiles an XYZ overlay with a contour-interval preference', about(
        `Requests the XYZ product with \`overlayPreference=xyz:contourInterval=[0.1,0.3]\` (test/auto-test-servlet.sh). Passes when the variant is cached under the product's entry (\`cfg/<hash>\`), the entry's SHA1 is \`95416015...\` as recorded, and the variant's tree matches the golden.`,
        '@nominal', '@mission-data', '@golden', '@legacy', '@mission-library',
      ), async ({ tiler }, testInfo) => {
        const t = await legacyServlet(tiler, `image=${file('m20-navcam-xyz')}&overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D`);
        expect(t.cachePath, 'custom parameters are cached as a variant').toContain('/cfg/');
        expect(t.legacy).toBe('95416015d4757930716e287e8d81efc428a20fc0');
        expectGolden('servlet/m20-navcam-xyz contourInterval', treeDigest(t.dir), t.dzi);
        await attachPreview(testInfo, t.dir, `${file('m20-navcam-xyz')} contours`, true);
      });

      test('servlet tiles a Mastcam-Z product with an explicit extrema stretch', about(
        `Requests the Mastcam-Z FDR with \`extremaStretch=true&stretchLow=1000&stretchHigh=3000\` (test/auto-test-servlet.sh). Passes when the entry's SHA1 is \`5dfc42f6...\` as recorded and the variant's tree matches the golden.`,
        '@nominal', '@mission-data', '@golden', '@legacy',
      ), async ({ tiler }, testInfo) => {
        const t = await legacyServlet(tiler, `image=${file('m20-zcam-575881')}&extremaStretch=true&stretchLow=1000&stretchHigh=3000`);
        expectLegacy(t.legacy, '5dfc42f6030e383b951a832f39e16c3d2e49f961');
        expectGolden('servlet/m20-zcam-575881 extrema 1000-3000', treeDigest(t.dir), t.dzi);
        await attachPreview(testInfo, t.dir, `${file('m20-zcam-575881')} 1000-3000`, true);
      });

      test('file://PATH reaches the same cache entry as PATH', about(
        `Requests the Navcam FDR as \`file://<file>\` after it was tiled as \`<file>\` (test/auto-test-servlet.sh). Passes when the DZI names the same cache entry and the entry still hashes to the recorded \`0790b191...\`.`,
        '@nominal', '@mission-data', '@legacy',
      ), async ({ tiler }) => {
        const plain = await tiler.tile(`image=${file('m20-navcam-nlg-fdr')}`);
        const t = await legacyServlet(tiler, `image=file://${file('m20-navcam-nlg-fdr')}`);
        expect(t.cachePath).toBe(plain.cachePath);
        expectLegacy(t.legacy, '0790b191c33408a4ae268d678b6077c6a1c079c7');
      });
    }
  });
}
