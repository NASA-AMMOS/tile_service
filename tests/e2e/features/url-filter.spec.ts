// Mission filters applied with filter=true.
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';

test.describe('Product filters', feature('url-filter'), () => {
  test('filter=true refuses products in ignored subdirectories', about(
    `The localhost configuration ignores the subdirectories \`ids-pipeline,browse,orbital\`. Requests \`image=00051/browse/x.IMG&filter=true\`. Passes when it answers 400 (rejected by filter), while the same request without \`filter\` gets past the filter and answers 404 (no such file).`,
    '@nominal', '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=00051/browse/x.IMG&filter=true')).status()).toBe(400);
    expect((await tiler.get('?image=00051/browse/x.IMG')).status()).toBe(404);
  });

  test('filter=true still tiles products the filters allow', about(
    `Requests the BYTE ramp with \`filter=true\`. Passes when it answers 200 with the DZI.`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('?image=synthetic/byte_rgb.vic&filter=true');
    expect(r.status()).toBe(200);
    expect(await r.text()).toContain('<Image');
  });
});
