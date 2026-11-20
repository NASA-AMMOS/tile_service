// SSRF and the URL whitelist: image URLs that would make the service fetch from
// itself, its network or a cloud metadata endpoint are refused before any
// connection. All hosts here are IP literals or reserved names, so the tests
// make no outbound connections (only the .invalid case does a DNS lookup).
// The cases are those of test/auto-test-servlet.sh, plus S3 look-alikes.
import { expect, test } from '../support/test';
import { about, feature, issue } from '../support/features';

/** [URL, why it is internal] */
const SSRF: [string, string][] = [
  ['http://localhost:8080/x.IMG', 'localhost'],
  ['http://LOCALHOST.:8080/x.IMG', 'localhost, upper case with a trailing dot'],
  ['http://foo.localhost/x.IMG', 'a *.localhost name'],
  ['http://127.0.0.1:8080/x.IMG', 'IPv4 loopback'],
  ['http://127.1/x.IMG', 'IPv4 loopback, short form'],
  ['http://2130706433/x.IMG', 'IPv4 loopback as one decimal number'],
  ['http://0.0.0.0/x.IMG', 'the unspecified address'],
  ['http://10.1.2.3/x.IMG', 'private 10/8'],
  ['http://172.16.0.1/x.IMG', 'private 172.16/12'],
  ['http://192.168.1.1/x.IMG', 'private 192.168/16'],
  ['http://100.100.100.200/x.IMG', 'carrier-grade NAT 100.64/10'],
  ['http://169.254.169.254/latest/meta-data/x.IMG', 'the AWS instance metadata endpoint'],
  ['http://metadata.google.internal/x.IMG', 'the GCP metadata host name'],
  ['http://example.com@127.0.0.1/x.IMG', 'loopback behind userinfo'],
  ['http://[::1]:8080/x.IMG', 'IPv6 loopback'],
  ['http://[fe80::1]/x.IMG', 'IPv6 link-local'],
  ['http://[fd00:ec2::254]/x.IMG', 'the AWS IPv6 metadata endpoint'],
  ['http://[::ffff:169.254.169.254]/x.IMG', 'IPv4-mapped IPv6 metadata address'],
  ['http://[64:ff9b::a9fe:a9fe]/x.IMG', 'NAT64 form of the metadata address'],
  ['http://[2002:a9fe:a9fe::]/x.IMG', '6to4 form of the metadata address'],
  ['http://no-such-host.invalid/x.IMG', 'a name that does not resolve'],
];

test.describe('SSRF filter', feature('security-ssrf'), () => {
  for (const [url, why] of SSRF) {
    test(`refuses ${url}`, about(
      `Requests \`image=${url}\` (${why}), percent-encoded, a URL the localhost whitelist (\`https?://.*\`) would otherwise allow. Passes on 400: the SSRF filter rejects every non-public address before connecting.`,
      '@security', '@legacy',
    ), async ({ tiler }) => {
      issue('NASA-AMMOS/tile_service#4', 'SSRF filter');
      expect((await tiler.get(`?image=${encodeURIComponent(url)}`)).status()).toBe(400);
    });
  }

  test('refuses an internal address reached by an unencoded URL', about(
    `The ASTRIA viewer puts image URLs into the query without encoding them. Requests \`image=http://169.254.169.254/latest/meta-data/x.IMG\` unencoded. Passes on 400.`,
    '@security',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=http://169.254.169.254/latest/meta-data/x.IMG')).status()).toBe(400);
  });

  test('an S3 look-alike host is treated as http, not S3', about(
    `Requests \`image=http://x.invalid?.s3.amazonaws.com/x.IMG\`: the authority ends at "?", so the host is x.invalid, not an S3 endpoint. Passes on 400 (S3 is disabled, and as http the host does not resolve), so S3 handling cannot be used to bypass the SSRF filter.`,
    '@security',
  ), async ({ tiler }) => {
    expect((await tiler.get(`?image=${encodeURIComponent('http://x.invalid?.s3.amazonaws.com/x.IMG')}`)).status()).toBe(400);
  });
});

test.describe('URL whitelist', feature('security-ssrf'), () => {
  test('refuses a scheme outside rdr_url_whitelist_patterns', about(
    `Requests \`image=ftp://example.com/x.IMG\` (test/auto-test-servlet.sh). The localhost whitelist allows only http(s), file:// and plain paths. Passes on 400.`,
    '@security', '@legacy',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=ftp%3A%2F%2Fexample.com%2Fx.IMG')).status()).toBe(400);
  });

  test('refuses other URL schemes', about(
    `Requests \`jar:\`, \`gopher:\` and \`data:\` image URLs. Passes when each answers 400.`,
    '@security',
  ), async ({ tiler }) => {
    for (const u of ['jar:http://example.com/x.jar!/x.IMG', 'gopher://example.com/x.IMG', 'data:text/plain,x.IMG']) {
      expect((await tiler.get(`?image=${encodeURIComponent(u)}`)).status(), u).toBe(400);
    }
  });
});
