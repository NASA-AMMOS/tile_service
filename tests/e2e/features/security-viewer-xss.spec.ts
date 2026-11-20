// Reflected content in the viewer page: viewer=true copies the request's query
// into script strings and attributes. Requests are sent byte for byte (a browser
// would percent-encode " < > ' itself). The suite's Tomcat has a second connector
// with relaxedQueryChars, as some proxied deployments configure it, so the
// characters stock Tomcat rejects can reach the servlet. Builds without the fix
// (branch fix/path-traversal-log-xss) fail these tests; that failure is the finding.
import { expect, test } from '../support/test';
import { about, feature, issue } from '../support/features';
import { server } from '../support/server';

const IMG = 'synthetic/byte_rgb.vic';
const FIX = 'fix/path-traversal-log-xss';

test.describe('Viewer reflected content', feature('security-viewer-xss'), () => {
  test('a quote in the query reaches the page percent-encoded', about(
    `Sends \`?image=${IMG}&viewer=true&x=a'b\` as raw bytes to stock Tomcat, which accepts a raw \`'\`. Passes on 200 when the page contains \`x=a%27b\` and not \`x=a'b\`, so the value cannot end a quoted string or attribute.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'Utils.encodeUnsafeUrlChars on the viewer PATH and PARAMS');
    const r = await tiler.raw(`?image=${IMG}&viewer=true&x=a'b`);
    expect(r.status).toBe(200);
    expect(r.body).not.toContain("x=a'b");
    expect(r.body).toContain('x=a%27b');
  });

  test('a script-breaking query is not reflected raw', about(
    `Sends \`?image=${IMG}&viewer=true&x=");window.__xss=1;//\` raw to the relaxed connector. Passes when the page does not contain the payload verbatim (its \`"\` must arrive as \`%22\`).`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'Utils.encodeUnsafeUrlChars on the viewer PATH and PARAMS');
    const r = await tiler.raw(`?image=${IMG}&viewer=true&x=");window.__xss=1;//`, server().relaxedUrl);
    expect(r.status).toBe(200);
    expect(r.body).not.toContain('");window.__xss=1;//');
  });

  for (const [name, payload] of [
    ['a string-breaking payload', '");window.__xss=1;//'],
    ['a tag-breaking payload', '</script><script>window.__xss=1</script>'],
  ]) {
    test(`${name} does not run script in the viewer`, about(
      `Fetches \`?image=${IMG}&viewer=true&x=${payload}\` raw from the relaxed connector and opens that page in headless Chromium at its own URL (the page's other requests go to the service). Passes when \`window.__xss\` stays undefined, i.e. the reflected query cannot run script in the service's origin.`,
      '@security', '@regression', '@browser',
    ), async ({ page, tiler }) => {
      issue(FIX, 'Utils.encodeUnsafeUrlChars on the viewer PATH and PARAMS');
      const target = `?image=${IMG}&viewer=true&x=${payload}`;
      const r = await tiler.raw(target, server().relaxedUrl);
      expect(r.status).toBe(200);
      const pageUrl = `${server().relaxedUrl}/image_tiler?image=${IMG}&viewer=true&x=payload`;
      await page.route(pageUrl, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: r.body }));
      await page.goto(pageUrl);
      await page.waitForTimeout(500);
      expect(await page.evaluate(() => (window as unknown as { __xss?: number }).__xss)).toBeUndefined();
    });
  }

  test('stock Tomcat refuses raw " < > in the query', about(
    `Sends \`?image=${IMG}&viewer=true&x="<b>\` raw to the stock connector. Passes on 400 from Tomcat before the servlet runs: with default connector settings only characters such as \`'\` reach the viewer.`,
    '@security', '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.raw(`?image=${IMG}&viewer=true&x="<b>`)).status).toBe(400);
  });

  test('an already-encoded query is passed through unchanged', about(
    `Requests the viewer with \`overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D\`. Passes when the page passes that parameter on exactly as received, so encoding does not double-encode valid requests.`,
    '@nominal', '@regression',
  ), async ({ tiler }) => {
    const r = await tiler.raw(`?image=${IMG}&viewer=true&overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D`);
    expect(r.status).toBe(200);
    expect(r.body).toContain('overlayPreference=xyz%3AcontourInterval%3D%5B0.1,0.3%5D');
  });
});
