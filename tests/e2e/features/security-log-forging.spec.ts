// Log forging: CR and LF in request parameters must not start new lines in the
// service log (Tomcat's stdout/stderr, .cache/run/<label>/catalina.out). Each test
// sends a unique marker after an encoded newline and reads what the request
// added to the log. Builds without the fix (branch fix/path-traversal-log-xss)
// fail the prefix test; that failure is the finding.
import { randomBytes } from 'node:crypto';
import { expect, test } from '../support/test';
import { LIBRARY } from '../support/env';
import { about, feature, issue, needsReview } from '../support/features';
import { logOffset, logSince } from '../support/server';

const marker = () => `FORGED${randomBytes(4).toString('hex').toUpperCase()}`;

/** Sends a request, waits for its log lines, and returns them split the way a log reader would. */
async function logLinesOf(tiler: import('../support/server').Tiler, p: string, m: string) {
  const from = logOffset();
  await tiler.get(p);
  for (let i = 0; i < 30 && !logSince(from).includes(m.slice(0, 6)); i++) await new Promise((r) => setTimeout(r, 100));
  await new Promise((r) => setTimeout(r, 1000)); // the tiling task logs after the response
  const text = logSince(from);
  await test.info().attach('service log', { body: text, contentType: 'text/plain' });
  return text.split(/\r\n|\r|\n/);
}

/** Lines that begin with the marker, i.e. lines the request started. */
const forged = (lines: string[], m: string) => lines.filter((l) => l.startsWith(m));

test.describe('Log forging', feature('security-log-forging'), () => {
  test('a newline in image does not split the request prefix of log lines', about(
    `Requests \`image=x%0D%0A<marker> INFO fake.IMG\`. Every log line of a request carries a \`[<image>]\` prefix. Passes when no log line starts with the marker right after a line whose \`[\` prefix was cut open, i.e. the prefix is written with the CR/LF neutralised. A build that fails this lets any client write log lines that look like the service's own.`,
    '@security', '@regression',
  ), async ({ tiler }) => {
    issue('fix/path-traversal-log-xss', 'Utils.sanitizeForLog on the log prefix');
    const m = marker();
    const lines = await logLinesOf(tiler, `?image=x%0D%0A${m}%20INFO%20fake.IMG`, m);
    expect(lines.some((l) => l.includes(m)), 'the request was logged').toBe(true);
    const broken = lines.flatMap((l, i) => (l.startsWith(m) && i > 0 && /\[[^\]]*$/.test(lines[i - 1]) ? [`${lines[i - 1]}\n${l}`] : []));
    expect(broken, 'forged lines that continue a cut-open [prefix').toEqual([]);
  });

  test('a newline in image cannot start any log line', about(
    `Same request as above. Passes when no line of the log starts with the marker at all. Messages that quote the decoded file name (for example "error getting product type for <name>") still write it raw, so a forged line remains even with the prefix fixed.`,
    '@security',
  ), async ({ tiler }) => {
    // the warning comes from the mission library's product lookup; a stub build never logs it
    needsReview('DziParams.java:240,289', 'warning messages quote the decoded image name with its CR/LF', { expectFail: LIBRARY === 'mars' });
    const m = marker();
    const lines = await logLinesOf(tiler, `?image=x%0D%0A${m}%20INFO%20fake.IMG`, m);
    expect(forged(lines, m)).toEqual([]);
  });

  test('a newline in a finder parameter cannot start a log line', about(
    `Requests \`finder/edr?image=x%0A<marker>.IMG\` (a file that does not exist). Passes when no log line starts with the marker. The request prefix is logged encoded, but the "file not found: <path>" message quotes the decoded path.`,
    '@security',
  ), async ({ tiler }) => {
    needsReview('TileService.java', 'exception messages such as "file not found: <path>" are logged with the decoded parameter', { expectFail: true });
    const m = marker();
    const lines = await logLinesOf(tiler, `/finder/edr?image=x%0A${m}.IMG`, m);
    expect(forged(lines, m)).toEqual([]);
  });

  test('a newline in another parameter cannot start a log line', about(
    `Requests a tiled image with \`stretchType=x%0A<marker>\` (an invalid value). Passes on 400 with no log line starting with the marker.`,
    '@security',
  ), async ({ tiler }) => {
    const m = marker();
    const lines = await logLinesOf(tiler, `?image=synthetic/byte_rgb.vic&stretchType=x%0A${m}`, m);
    expect(forged(lines, m)).toEqual([]);
  });
});
