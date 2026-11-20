// /version, /image_descriptions.xml, /image_config.xml and /openseadragon.min.js.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { LIBRARY, REPO_ROOT } from '../support/env';

const pomVersion = () => readFileSync(path.join(REPO_ROOT, 'pom.xml'), 'utf8').match(/<semver>([^<]+)<\/semver>/)![1];

test.describe('Version and configuration resources', feature('static-resources'), () => {
  test('/version reports the build version', about(
    `Requests \`/image_tiler/version\`. Passes on 200 text whose version starts with the pom's \`<semver>\` (${'`'}${pomVersion()}${'`'}), the value the ingest pipeline and operators read.`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('/version');
    expect(r.status()).toBe(200);
    expect((await r.text()).trim()).toMatch(new RegExp(`^${pomVersion().replace(/\./g, '\\.')}`));
  });

  test('/image_descriptions.xml lists the product descriptions', about(
    `Requests \`/image_tiler/image_descriptions.xml\`, which the ASTRIA viewer reads for product descriptions and the metadata ingester polls as its health check. Passes on 200 XML with an \`<image_descriptions>\` root holding at least one entry (with mission-image-stub: an empty list marked \`library="stub"\`).`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('/image_descriptions.xml');
    expect(r.status()).toBe(200);
    expect(r.headers()['content-type']).toMatch(/xml/);
    const xml = await r.text();
    expect(xml).toMatch(/<image_descriptions[\s>]/);
    // mission-image-stub ships an empty list marked library="stub"
    if (LIBRARY === 'mars') expect(xml.match(/<\w+[^>]*>/g)!.length).toBeGreaterThan(3);
    else expect(xml).toMatch(/<image_descriptions[^>]*library="stub"/);
  });

  test('/image_config.xml is served', about(
    `Requests \`/image_tiler/image_config.xml\` (the mission library's image configuration). Passes on 200 XML.`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('/image_config.xml');
    expect(r.status()).toBe(200);
    expect(await r.text()).toMatch(/^\s*<\?xml|^\s*</);
  });

  test('/openseadragon.min.js is the bundled OpenSeadragon', about(
    `Requests \`/image_tiler/openseadragon.min.js\`. Passes on 200 JavaScript byte-identical to webapp/resources/openseadragon.min.js.`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('/openseadragon.min.js', { quiet: true });
    expect(r.status()).toBe(200);
    expect(r.headers()['content-type']).toMatch(/javascript/);
    expect((await r.body()).equals(readFileSync(path.join(REPO_ROOT, 'webapp', 'resources', 'openseadragon.min.js')))).toBe(true);
  });
});
