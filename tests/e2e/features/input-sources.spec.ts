// Where images come from: fs_input_dir paths, file:// URLs, public https URLs, S3.
import path from 'node:path';
import { expect, test } from '../support/test';
import { asset, manifestAssets } from '../support/data';
import { about, feature } from '../support/features';
import { expectCompletePyramid } from '../support/tree';

test.describe('Input sources', feature('input-sources'), () => {
  test('file://PATH and PATH name the same image and cache entry', about(
    `Requests the BYTE ramp as \`synthetic/byte_rgb.vic\` and as \`file://synthetic/byte_rgb.vic\`. Passes when both answer 200 with DZIs naming the same cache entry.`,
    '@nominal',
  ), async ({ tiler }) => {
    const a = await tiler.tile('image=synthetic/byte_rgb.vic');
    const b = await tiler.tile('image=file://synthetic/byte_rgb.vic');
    expect(b.cachePath).toBe(a.cachePath);
  });

  test('an S3 URL is refused while S3 is disabled', about(
    `The localhost configuration sets \`enable_s3=false\`. Requests \`image=s3://bucket/x.IMG\` and the virtual-host form \`https://bucket.s3.amazonaws.com/x.IMG\`. Passes when both answer 400 without any network access.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=s3%3A%2F%2Fbucket%2Fx.IMG')).status()).toBe(400);
    expect((await tiler.get('?image=https%3A%2F%2Fbucket.s3.amazonaws.com%2Fx.IMG')).status()).toBe(400);
  });
});

test.describe('Input sources over the network', feature('input-sources', 'full'), () => {
  test('a public https product URL is tiled', about(
    `Requests the Navcam FDR by its public PDS URL (\`image=https://...cloudfront.net/.../NLG_...IMG\`, as the viewer sends for archive products). Passes when the service answers 200, reads the product over https, and the tiling completes with the same size as the local copy.`,
    '@nominal', '@mission-data', '@network',
  ), async ({ tiler }) => {
    const nlg = manifestAssets().find((a) => a.id === 'm20-navcam-nlg-fdr')!;
    const local = await tiler.tile(`image=alt/${path.basename(asset('m20-navcam-nlg-fdr'))}`);
    const t = await tiler.tile(`image=${nlg.url}`);
    expect([t.dzi.width, t.dzi.height]).toEqual([local.dzi.width, local.dzi.height]);
    expectCompletePyramid(t.dir);
  });
});
