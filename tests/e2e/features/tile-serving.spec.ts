// Tiles served at the URL the DZI names, and the forms of that URL.
import { readFileSync, readdirSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { server } from '../support/server';
import { levels } from '../support/tree';

test.describe('Tile serving and DZI URLs', feature('tile-serving'), () => {
  test('every tile is served at the URL the DZI names', about(
    `Tiles the 3-band BYTE ramp with \`proxy=http\`, takes the DZI's \`Url\` (\`http://<host>:<port>/image_tiler/<hash>/http_proxy_image_files/\`) and fetches \`<Url><level>/<col>_<row>.png\` for every tile of every level, as OpenSeadragon does. Passes when each answers 200 \`image/png\` with exactly the bytes of the cached tile.`,
    '@nominal',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/byte_rgb.vic&proxy=http');
    expect(t.dzi.url).toBe(`${server().baseUrl}/image_tiler/${t.cachePath}/http_proxy_image_files/`);
    let n = 0;
    for (const l of levels(t.dzi.width, t.dzi.height)) {
      for (const f of readdirSync(path.join(t.dir, 'image_files', String(l.level)))) {
        const r = await tiler.request.get(`${t.dzi.url}${l.level}/${f}`);
        expect(r.status(), `${l.level}/${f}`).toBe(200);
        expect(r.headers()['content-type']).toBe('image/png');
        expect((await r.body()).equals(readFileSync(path.join(t.dir, 'image_files', String(l.level), f))), `${l.level}/${f} bytes`).toBe(true);
        n++;
      }
    }
    expect(n).toBeGreaterThan(8);
  });

  test('proxy selects the http or https form of the tile URL', about(
    `Requests the same DZI with \`proxy=http\`, \`proxy=https\` and no proxy. Passes when the \`Url\` is \`http://...\`/http_proxy_image_files, \`https://...\`/https_proxy_image_files, and, without proxy, an https URL (\`default_proxy_image_files\` while the tiling runs, \`https_proxy_image_files\` once cached), which is why local deployments need proxy=http.`,
    '@nominal',
  ), async ({ tiler }) => {
    const url = async (q: string) => (await (await tiler.get(`?image=synthetic/byte_rgb.vic${q}`)).text()).match(/Url="([^"]+)"/)?.[1];
    expect(await url('&proxy=http')).toMatch(/^http:\/\/[^/]+\/image_tiler\/[0-9a-f]{40}\/http_proxy_image_files\/$/);
    expect(await url('&proxy=https')).toMatch(/^https:\/\/[^/]+\/image_tiler\/[0-9a-f]{40}\/https_proxy_image_files\/$/);
    expect(await url('')).toMatch(/^https:\/\/[^/]+\/image_tiler\/[0-9a-f]{40}\/(default|https)_proxy_image_files\/$/);
  });

  test('a tile that does not exist is 404', about(
    `Requests a tile beyond the grid (\`<hash>/http_proxy_image_files/0/9_9.png\`) of a tiled image, and a tile of a hash that was never tiled. Passes when both answer 404.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/byte_rgb.vic&proxy=http');
    expect((await tiler.get(`/${t.cachePath}/http_proxy_image_files/0/9_9.png`)).status()).toBe(404);
    expect((await tiler.get(`/${'0'.repeat(40)}/http_proxy_image_files/0/0_0.png`)).status()).toBe(404);
  });

  test('an unknown path under /image_tiler is refused', about(
    `Requests \`/image_tiler/no/such/endpoint\` and \`/image_tiler\` with no image. Passes when both answer 400 (unrecognized request), not 200 or 500.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('/no/such/endpoint')).status()).toBe(400);
    expect((await tiler.get('')).status()).toBe(400);
  });
});
