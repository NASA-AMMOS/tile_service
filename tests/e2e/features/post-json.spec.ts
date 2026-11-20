// Tiling requests as a JSON POST body.
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { freshCopy } from '../support/synthetic';

test.describe('JSON POST requests', feature('post-json'), () => {
  test('a JSON body with rdr_url tiles the image', about(
    `POSTs \`{"rdr_url": "<image>"}\` as application/json to \`/image_tiler?image=<image>\` (the service routes on the query parameter and reads the options from the body). Passes when it answers 200 with the DZI and the tiling completes.`,
    '@nominal',
  ), async ({ tiler }) => {
    const f = freshCopy('byte_rgb.vic', 'post');
    const r = await tiler.post(`?image=${f.image}`, { rdr_url: f.image });
    expect(r.status()).toBe(200);
    expect(await r.text()).toContain('<Image');
    await tiler.waitDone(`image=${f.image}`);
  });

  test('body options apply: tile_format', about(
    `POSTs \`{"rdr_url": "<image>", "tile_format": "jpg"}\`. Passes when the DZI declares \`Format="jpg"\`.`,
    '@nominal',
  ), async ({ tiler }) => {
    const f = freshCopy('byte_rgb.vic', 'post-jpg');
    const r = await tiler.post(`?image=${f.image}`, { rdr_url: f.image, tile_format: 'jpg' });
    expect(r.status()).toBe(200);
    expect(await r.text()).toMatch(/Format="jpg"/);
  });

  test('a POST without the image query parameter is refused', about(
    `POSTs a valid JSON body to \`/image_tiler\` with no \`image\` parameter, and a non-JSON body with one. Passes when both answer 400.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.post('', { rdr_url: 'synthetic/byte_rgb.vic' })).status()).toBe(400);
    expect((await tiler.post('?image=synthetic/byte_rgb.vic', 'rdr_url=x', 'text/plain')).status()).toBe(400);
  });
});
