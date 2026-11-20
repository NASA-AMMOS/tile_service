// thumb=true and metadata=true, as the ASTRIA viewer requests them.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { asset } from '../support/data';
import { LIBRARY } from '../support/env';
import { about, feature, needsReview } from '../support/features';
import { decode } from '../support/tree';

const NLG = () => `alt/${path.basename(asset('m20-navcam-nlg-fdr'))}`;

/** Fields the viewer's histogram and stretch controls read (astria_ui src/actions/imageStretch.js), plus the basics. */
const VIEWER_FIELDS = ['width', 'height', 'histogram', 'histogram_bins', 'histogram_low_inclusive', 'histogram_high_exclusive',
  'stretch_type', 'stretch_low', 'stretch_high', 'stretch_low_percent', 'stretch_high_percent', 'product_type', 'rdr_type',
  'original_bits', 'original_bands', 'cache_hash', 'tiler_version', 'is_overlay'];

test.describe('Thumbnails and metadata', feature('thumbnail-metadata'), () => {
  test('thumb=true returns the PNG thumbnail of a tiled product', about(
    `Tiles the public Navcam FDR, then requests \`thumb=true\`. Passes when the response is 200 \`image/png\`, byte-identical to the cached thumbnail.png, 160 pixels high (the default) and as wide as the image's aspect ratio implies.`,
    '@nominal', '@mission-data',
  ), async ({ tiler }, testInfo) => {
    const t = await tiler.tile(`image=${NLG()}`);
    const r = await tiler.get(`?image=${NLG()}&thumb=true`);
    expect(r.status()).toBe(200);
    expect(r.headers()['content-type']).toBe('image/png');
    const body = await r.body();
    expect(body.equals(readFileSync(path.join(t.dir, 'thumbnail.png')))).toBe(true);
    const d = decode(body);
    expect(d.height).toBe(160);
    expect(Math.abs(d.width - (160 * t.dzi.width) / t.dzi.height)).toBeLessThanOrEqual(1);
    await testInfo.attach('Navcam thumbnail preview [mission data]', { body, contentType: 'image/png' });
  });

  test('metadata=true returns metadata.json with the fields the viewer reads', about(
    `Requests \`metadata=true\` for the tiled Navcam FDR. Passes when the response is 200 JSON equal to the cached metadata.json, with ${VIEWER_FIELDS.map((f) => `\`${f}\``).join(', ')}; width and height match the DZI, \`histogram\` has one array of \`histogram_bins\` counts per band, and the counts add up to at most width x height.`,
    '@nominal', '@mission-data',
  ), async ({ tiler }) => {
    const t = await tiler.tile(`image=${NLG()}`);
    const r = await tiler.get(`?image=${NLG()}&metadata=true`);
    expect(r.status()).toBe(200);
    expect(r.headers()['content-type']).toMatch(/json/);
    const md = await r.json();
    expect(md).toEqual(JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8')));
    for (const f of VIEWER_FIELDS) expect(md, `field ${f}`).toHaveProperty(f);
    expect([md.width, md.height]).toEqual([t.dzi.width, t.dzi.height]);
    expect(md.histogram).toHaveLength(md.original_bands);
    for (const h of md.histogram) {
      expect(h).toHaveLength(md.histogram_bins);
      expect(h.reduce((a: number, b: number) => a + b, 0)).toBeLessThanOrEqual(md.width * md.height);
    }
    // the product type is parsed from the file name by the mission library; the stub calls everything an EDR
    expect([md.product_type, md.rdr_type, md.is_overlay]).toEqual(LIBRARY === 'mars' ? ['FDR', 'fdr', false] : ['EDR', 'edr', false]);
  });

  test('the metadata documents the README field rdr_size', about(
    `README "Metadata JSON" lists \`rdr_size\` for the input size in bytes. Passes when metadata.json has that field. The service writes \`rdr_bytes\` instead, so the README and the code disagree.`,
    '@nominal', '@regression',
  ), async ({ tiler }) => {
    needsReview('README.md', 'documents metadata field rdr_size, the code writes rdr_bytes', { expectFail: true });
    const t = await tiler.tile('image=synthetic/byte_rgb.vic');
    const md = JSON.parse(readFileSync(path.join(t.dir, 'metadata.json'), 'utf8'));
    expect(md).toHaveProperty('rdr_size');
  });
});
