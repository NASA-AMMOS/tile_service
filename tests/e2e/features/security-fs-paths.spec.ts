// Tiling requests may only read files under fs_input_dir. The servlet runs in
// .cache/run/<label>/srv with fs_input_dir=test; srv/outside/ holds readable
// files that no request may reach.
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature, needsReview } from '../support/features';
import { OUTSIDE_DIR, OUTSIDE_SECRET, SRV_DIR } from '../support/server';

const NLG = 'NLG_1618_0810580175_755FDR_N0790198NCAM00518_0A02I3J01.IMG';

test.describe('Local file paths', feature('security-fs-paths'), () => {
  test('absolute paths are refused, with or without file://', about(
    `Requests \`/etc/passwd.IMG\` and \`file:///etc/passwd.IMG\` (test/auto-test-servlet.sh). Passes when both answer 400. Resolved under fs_input_dir they would be 404, so 400 shows they are refused as absolute.`,
    '@security', '@legacy',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=%2Fetc%2Fpasswd.IMG')).status()).toBe(400);
    expect((await tiler.get('?image=file%3A%2F%2F%2Fetc%2Fpasswd.IMG')).status()).toBe(400);
  });

  test('absolute paths of files that exist are refused', about(
    `Requests the absolute path of a product the service can read (\`<srv>/test/${NLG}\`), as is and as \`file://<path>\` (test/auto-test-servlet.sh), and the absolute path of a file outside fs_input_dir. Passes when each answers 400.`,
    '@security', '@legacy',
  ), async ({ tiler }) => {
    const inside = path.join(SRV_DIR, 'test', NLG);
    for (const p of [inside, `file://${inside}`, path.join(OUTSIDE_DIR, OUTSIDE_SECRET)]) {
      expect((await tiler.get(`?image=${encodeURIComponent(p)}`)).status(), p).toBe(400);
    }
  });

  test('.. segments are refused', about(
    `Requests \`../outside/${OUTSIDE_SECRET}\` (a readable VICAR file next to fs_input_dir), \`synthetic/../../outside/${OUTSIDE_SECRET}\` and \`../x.IMG\`, encoded and not. Passes when each answers 400 whether or not the file exists.`,
    '@security',
  ), async ({ tiler }) => {
    for (const p of [`../outside/${OUTSIDE_SECRET}`, `synthetic/../../outside/${OUTSIDE_SECRET}`, '../x.IMG', `file://../outside/${OUTSIDE_SECRET}`]) {
      expect((await tiler.get(`?image=${p}`)).status(), p).toBe(400);
      expect((await tiler.get(`?image=${encodeURIComponent(p)}`)).status(), `${p} encoded`).toBe(400);
    }
  });

  test('a NUL byte in the path is refused', about(
    `Requests \`image=synthetic/byte_rgb.vic%00.png\`. Passes on 400 (an invalid path), not 200 or 500.`,
    '@security', '@malformed-input',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/byte_rgb.vic%00.png')).status()).toBe(400);
  });

  test('a JSON POST cannot name a file outside fs_input_dir', about(
    `POSTs \`{"rdr_url": "../outside/${OUTSIDE_SECRET}"}\` to \`?image=synthetic/byte_rgb.vic\`. The filters check the \`image\` query parameter, but the tiling uses the body's \`rdr_url\`. Passes when the service answers 400 and tiles nothing from outside fs_input_dir.`,
    '@security',
  ), async ({ tiler }) => {
    needsReview('TileService.java:779', 'serveDZI filters request.getParameter("image") but a POST tiles the body rdr_url, which skips the .. check, the whitelist and the absolute-path check', { expectFail: true });
    const r = await tiler.post('?image=synthetic/byte_rgb.vic', { rdr_url: `../outside/${OUTSIDE_SECRET}` });
    expect(r.status()).toBe(400);
  });
});
