// /finder/label: a product's VICAR label as JSON.
import { createHash } from 'node:crypto';
import { expect, test } from '../support/test';
import { about, feature, needsReview } from '../support/features';

const RAS = '00052/ids/rdr/ncam/NLF_0052_0671564560_659RAS_N0032046NCAM03052_0A0195J02';

test.describe('VICAR label as JSON', feature('finder-label'), () => {
  test('the label of a Mars 2020 RDR', about(
    `Requests \`finder/label\` for the one non-empty file in the finder tree, a Navcam RAS RDR (test/auto-test-servlet.sh). Passes on 200 JSON with \`system\`, \`properties\` and \`tasks\`, whose SHA1 is the recorded \`f026735c...\`.`,
    '@nominal', '@legacy', '@golden', '@mission-data',
  ), async ({ tiler }) => {
    const r = await tiler.get(`/finder/label?image=${RAS}.IMG`);
    expect(r.status()).toBe(200);
    const body = await r.text();
    expect(Object.keys(JSON.parse(body))).toEqual(['system', 'properties', 'tasks']);
    expect(createHash('sha1').update(body).digest('hex')).toBe('f026735cff80df64565ae4f6be1f40b0771702d7');
  });

  test('the label of a missing file is 404', about(
    `Requests \`finder/label\` for \`${RAS}.NOT\` (test/auto-test-servlet.sh). Passes on 404.`,
    '@off-nominal', '@legacy',
  ), async ({ tiler }) => {
    expect((await tiler.get(`/finder/label?image=${RAS}.NOT`)).status()).toBe(404);
  });

  test('system items and property groups are reported with their types', about(
    `Requests \`finder/label\` for a synthetic VICAR file whose label has two property groups (IDENTIFICATION with strings and an integer array; CAMERA) and two history tasks. Passes when \`system\` holds the system items with integers as numbers (NL 8, NS 16) and \`properties\` holds the IDENTIFICATION group with its values typed.`,
    '@nominal',
  ), async ({ tiler }) => {
    const r = await tiler.get('/finder/label?image=synthetic/labeled.vic');
    expect(r.status()).toBe(200);
    const j = await r.json();
    expect([j.system.FORMAT, j.system.NL, j.system.NS, j.system.NB, j.system.ORG]).toEqual(['BYTE', 8, 16, 1, 'BSQ']);
    expect(j.properties.IDENTIFICATION).toEqual({ MISSION_NAME: 'SYNTHETIC', INSTRUMENT_ID: 'TESTCAM', FILTER_NUMBER: [1, 2, 3] });
  });

  test('real-valued label items keep their fraction', about(
    `Requests \`finder/label\` for the synthetic file whose CAMERA group has \`EXPOSURE=1.5\`. Passes when the JSON value is 1.5. The parser tries its integer pattern before its real pattern, so it currently reports 1 (and skips ".5").`,
    '@nominal',
  ), async ({ tiler }) => {
    needsReview('Finder.java:651', 'real label values are truncated to integers: the integer alternative is tried before the real one', { expectFail: true });
    const j = await (await tiler.get('/finder/label?image=synthetic/labeled.vic')).json();
    expect(j.properties.CAMERA).toEqual({ EXPOSURE: 1.5 });
  });

  test('every history task is reported', about(
    `Requests \`finder/label\` for the synthetic file with two history tasks (GEN, then STRETCH). Passes when \`tasks\` lists both, in order, with their items. The parser currently drops the last task.`,
    '@nominal',
  ), async ({ tiler }) => {
    needsReview('Finder.java:451', 'getVicarLabel never adds the last TASK group (nor the last PROPERTY group of a label without tasks)', { expectFail: true });
    const j = await (await tiler.get('/finder/label?image=synthetic/labeled.vic')).json();
    expect(j.tasks.map((t: { TASK: string }) => t.TASK)).toEqual(['GEN', 'STRETCH']);
    expect(j.tasks[1].LIMITS).toEqual([0, 255]);
  });

  test('every property group of a label without history is reported', about(
    `Requests \`finder/label\` for a synthetic file with two property groups and no history tasks. Passes when \`properties\` holds both groups. The parser currently drops the last one.`,
    '@nominal',
  ), async ({ tiler }) => {
    needsReview('Finder.java:451', 'getVicarLabel never adds the last TASK group (nor the last PROPERTY group of a label without tasks)', { expectFail: true });
    const j = await (await tiler.get('/finder/label?image=synthetic/properties_only.vic')).json();
    expect(Object.keys(j.properties)).toEqual(['IDENTIFICATION', 'CAMERA']);
  });

  test('a file that is not VICAR is a client error', about(
    `Requests \`finder/label\` for a text file named \`not_an_image.vic\`. Passes on a 4xx status: the request names a file that has no VICAR label. The service currently answers 500 ("unrecognized start").`,
    '@malformed-input',
  ), async ({ tiler }) => {
    needsReview('Finder.java', 'a non-VICAR file gives 500 (IOException) instead of a 4xx', { expectFail: true });
    const s = (await tiler.get('/finder/label?image=synthetic/not_an_image.vic')).status();
    expect(s).toBeGreaterThanOrEqual(400);
    expect(s).toBeLessThan(500);
  });
});
