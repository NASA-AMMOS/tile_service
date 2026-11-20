// The built-in OpenSeadragon viewer, loaded in headless Chromium.
import path from 'node:path';
import { expect, test } from '../support/test';
import { asset } from '../support/data';
import { about, feature } from '../support/features';
import { tilerUrl } from '../support/server';

test.describe('Built-in viewer', feature('viewer-page'), () => {
  test('viewer=true shows the image with OpenSeadragon', about(
    `Tiles the public Navcam FDR, then opens \`?image=<file>&viewer=true&proxy=http\` in headless Chromium. Passes when the page loads openseadragon.min.js from the service, OpenSeadragon draws the image (its canvas has a non-zero size and tile requests to the service answer 200), and no request the page makes fails.`,
    '@nominal', '@browser', '@mission-data',
  ), async ({ page, tiler }, testInfo) => {
    const image = `alt/${path.basename(asset('m20-navcam-nlg-fdr'))}`;
    await tiler.tile(`image=${image}&proxy=http`);
    const failed: string[] = [];
    const tiles: number[] = [];
    page.on('response', (r) => {
      if (r.status() >= 400) failed.push(`${r.status()} ${r.url()}`);
      if (/_proxy_image_files\//.test(r.url())) tiles.push(r.status());
    });
    page.on('pageerror', (e) => failed.push(`page error: ${e.message}`));
    await page.goto(tilerUrl(`?image=${image}&viewer=true&proxy=http`));
    await expect(page.locator('.openseadragon-canvas canvas').first()).toBeVisible({ timeout: 30_000 });
    await expect.poll(() => tiles.length, { timeout: 30_000 }).toBeGreaterThan(0);
    await page.waitForLoadState('networkidle');
    const box = await page.locator('.openseadragon-canvas canvas').first().boundingBox();
    expect(box && box.width * box.height).toBeGreaterThan(0);
    expect(tiles.every((s) => s === 200), `tile statuses ${tiles.join(',')}`).toBe(true);
    expect(failed).toEqual([]);
    await testInfo.attach('viewer screenshot preview [mission data]', { body: await page.screenshot(), contentType: 'image/png' });
  });

  test('the viewer page is served before the image is checked', about(
    `Requests \`viewer=true\` for an image that does not exist. The page itself is static (the image's tiles load later from the browser). Passes when the page answers 200 HTML that references the image.`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('?image=synthetic/does_not_exist.vic&viewer=true');
    expect(r.status()).toBe(200);
    expect(r.headers()['content-type']).toMatch(/html/);
    expect(await r.text()).toContain('image=synthetic/does_not_exist.vic');
  });
});
