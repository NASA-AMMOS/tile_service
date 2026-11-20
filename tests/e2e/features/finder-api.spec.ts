// The /finder endpoints, over the Mars 2020 tree in test/finder-data.zip
// (sols 50-52, mostly empty files). The SHA1s are those of test/auto-test-servlet.sh.
import { createHash } from 'node:crypto';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';

const sha1 = (s: string) => createHash('sha1').update(s).digest('hex');

const ZL0 = '00051/ids/edr/zcam/ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01';
const NLF_EDR = '00051/ids/fdr/ncam/NLF_0051_0671466170_380FDR_N0031950NCAM00408_0A00LLJ01';
const NLF_RDR = '00051/ids/rdr/ncam/NLF_0051_0671466170_380XYM_N0031950NCAM00408_0A00LLJ01';

/** [query, sha1 of the JSON body] from test/auto-test-servlet.sh. */
const LEGACY_JSON: [string, string, string][] = [
  ['finder/solrange', 'a7b2f2b865a55adc05a02fedf40049389f76f7c7', 'the first and last sol in the tree'],
  ['finder/edrs?sol=50', 'bd44ee0afbd93ee64dc6ad294e52e2f6a3c29a75', 'the EDRs of sol 50 with their metadata'],
  ['finder/edrs?sol=00051', '528e05d4fb1281d09622d8692b27b975f265fdf3', 'the EDRs of sol 51, given zero-padded'],
  ['finder/edrs?sol=40', '39c689ba1f17a1686e7d1cb880a69a9665579e3f', 'an empty list for a sol outside the tree'],
  [`finder/edr?image=${ZL0}.IMG`, 'b40e82fc41bd0fa2b9e77589d87abdf219e5864c', 'an EDR and the metadata parsed from its name'],
  [`finder/rdrs?edr=${NLF_EDR}.IMG`, '3fb2d9f8b7cd59f75da4e0cf83fcafc14d725e3c', 'the RDRs derived from an EDR'],
  [`finder/rdr?image=${NLF_RDR}.IMG`, '250e89adae2be4bcc4d0655057ca0144fda26296', 'an RDR and its metadata'],
];
const LEGACY_404 = [`finder/edr?image=${ZL0}.NOT`, `finder/rdrs?edr=${NLF_EDR}.NOT`, `finder/rdr?image=${NLF_RDR}.NOT`];

test.describe('Product finder', feature('finder-api'), () => {
  for (const [q, want, what] of LEGACY_JSON) {
    test(`${q.split('?')[0]} returns ${what}`, about(
      `Requests \`/image_tiler/${q}\` (test/auto-test-servlet.sh). Passes on 200 JSON whose SHA1 is the recorded \`${want.slice(0, 12)}...\`.`,
      '@nominal', '@legacy', '@golden', '@mission-library',
    ), async ({ tiler }) => {
      const r = await tiler.get(`/${q}`);
      expect(r.status()).toBe(200);
      const body = await r.text();
      JSON.parse(body);
      expect(sha1(body)).toBe(want);
    });
  }

  for (const q of LEGACY_404) {
    test(`${q.split('?')[0]} of a missing file is 404`, about(
      `Requests \`/image_tiler/${q}\`, a product that is not in the tree (test/auto-test-servlet.sh). Passes on 404.`,
      '@off-nominal', '@legacy',
    ), async ({ tiler }) => {
      expect((await tiler.get(`/${q}`)).status()).toBe(404);
    });
  }

  test('an EDR\'s metadata is parsed from its file name', about(
    `Requests \`finder/edr\` for the Mastcam-Z EDR \`ZL0_0051_...\`. Passes when the JSON names the EDR path and its metadata gives sol 51 and the instrument and product type the file name encodes, the fields the metadata ingester copies into ElasticSearch.`,
    '@nominal', '@mission-library',
  ), async ({ tiler }) => {
    const j = await (await tiler.get(`/finder/edr?image=${ZL0}.IMG`)).json();
    expect(j.edr).toContain('ZL0_0051_0671470568_053EBY');
    expect(j.metadata.sol).toBe(51);
    expect(JSON.stringify(j.metadata)).toMatch(/ZCAM|zcam|Mastcam/i);
  });

  test('bad requests are refused with 400', about(
    `Requests \`finder/edrs?sol=abc\`, \`finder/edr\` with no image, and \`finder/bogus\`. Passes when each answers 400.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    for (const q of ['finder/edrs?sol=abc', 'finder/edr', 'finder/bogus']) expect((await tiler.get(`/${q}`)).status(), q).toBe(400);
  });
});
