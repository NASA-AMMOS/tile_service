// The /finder endpoints may only read files under fs_input_dir. The servlet runs
// in .cache/run/<label>/srv with fs_input_dir=test; srv/outside/ holds a VICAR
// file whose label carries SECRET_MARKER, and a copy under a Mars 2020 EDR name.
// These tests assert the secure behaviour. Builds without the path traversal fix
// (branch fix/path-traversal-log-xss) fail them; that failure is the finding.
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature, issue } from '../support/features';
import { OUTSIDE_DIR, OUTSIDE_EDR, OUTSIDE_SECRET, SECRET_MARKER } from '../support/server';

const ZL0 = '00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.IMG';
const FIX = 'fix/path-traversal-log-xss';

test.describe('Finder path traversal', feature('security-finder-paths'), () => {
  test('finder/label cannot read a VICAR label outside fs_input_dir', about(
    `Requests \`finder/label?image=../outside/${OUTSIDE_SECRET}\`, a readable VICAR file next to fs_input_dir whose label contains \`${SECRET_MARKER}\`. Passes on 400 with no part of that label in the response. A build that answers 200 here discloses any VICAR label the service account can read.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'finder paths are resolved and checked against fs_input_dir');
    const r = await tiler.get(`/finder/label?image=..%2Foutside%2F${OUTSIDE_SECRET}`);
    expect(await r.text(), 'label of a file outside fs_input_dir').not.toContain(SECRET_MARKER);
    expect(r.status()).toBe(400);
  });

  test('finder/edr cannot reach a product outside fs_input_dir', about(
    `Requests \`finder/edr?image=../outside/${OUTSIDE_EDR}\`, a file with a valid Mars 2020 EDR name next to fs_input_dir. Passes on 400; a build that answers 200 confirms the file exists and returns its metadata.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'finder paths are resolved and checked against fs_input_dir');
    expect((await tiler.get(`/finder/edr?image=..%2Foutside%2F${OUTSIDE_EDR}`)).status()).toBe(400);
  });

  test('.. segments are refused whether or not the target exists', about(
    `Requests \`finder/label?image=../../../../../../../../../../etc/passwd\`, \`finder/edr?image=00052/../../x.IMG\` and \`finder/rdr?image=00051/../../../x.IMG\`. Passes when each answers 400 before any file is opened. Without the check the status (404, or 500 for a file that exists but is not VICAR) reveals whether the path exists.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'finder paths are resolved and checked against fs_input_dir');
    for (const q of ['finder/label?image=..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2F..%2Fetc%2Fpasswd', 'finder/edr?image=00052%2F..%2F..%2Fx.IMG',
      'finder/rdr?image=00051/../../../x.IMG']) {
      expect((await tiler.get(`/${q}`)).status(), q).toBe(400);
    }
  });

  test('absolute paths are refused', about(
    `Requests \`finder/rdrs?edr=/etc/passwd\` and \`finder/label?image=<absolute path of the outside file>\`. Passes when both answer 400.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'finder paths are resolved and checked against fs_input_dir');
    expect((await tiler.get('/finder/rdrs?edr=%2Fetc%2Fpasswd')).status()).toBe(400);
    expect((await tiler.get(`/finder/label?image=${encodeURIComponent(path.join(OUTSIDE_DIR, OUTSIDE_SECRET))}`)).status()).toBe(400);
  });

  test('a leading slash is an absolute path, not a path under fs_input_dir', about(
    `Requests \`finder/edr?image=/${ZL0}\`: an existing product under fs_input_dir, written with a leading slash. Passes on 400. Note for deployments: the metadata ingester builds paths by removing the watch directory with no trailing slash, which leaves a leading slash, so a build that enforces this needs the ingester (or the service) to strip it.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue(FIX, 'compatibility: ingest paths with a leading slash change from 200 to 400');
    expect((await tiler.get(`/finder/edr?image=/${ZL0}`)).status()).toBe(400);
  });

  test('a NUL byte in a finder path is refused', about(
    `Requests \`finder/label?image=synthetic/labeled.vic%00x\`. Passes on 400 (invalid path).`,
    '@security', '@malformed-input',
  ), async ({ tiler }) => {
    expect((await tiler.get('/finder/label?image=synthetic/labeled.vic%00x')).status()).toBe(400);
  });

  test('ordinary relative paths still work', about(
    `Requests \`finder/edr?image=${ZL0}\` and \`finder/label?image=synthetic/labeled.vic\`. Passes when both answer 200, so the checks do not refuse valid paths.`,
    '@nominal', '@regression',
  ), async ({ tiler }) => {
    expect((await tiler.get(`/finder/edr?image=${ZL0}`)).status()).toBe(200);
    expect((await tiler.get('/finder/label?image=synthetic/labeled.vic')).status()).toBe(200);
  });
});
