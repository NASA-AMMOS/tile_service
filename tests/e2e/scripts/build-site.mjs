#!/usr/bin/env node
// Builds the GitHub Pages site: a tile_service home page (what it does, how it
// is used, endpoints, command line) ending in a capabilities table from
// features.yml and the latest test summary; the overview pages under
// site/overview/ (HTML copied as is, Markdown rendered); and the feature test
// report (report/) under tests/.
//
//   node scripts/build-site.mjs [--report report] [--out public]
import { cpSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { marked } from 'marked';
import { parse } from 'yaml';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const repoRoot = join(root, '..', '..');
const args = process.argv.slice(2);
const opt = (n, d) => (args.includes(n) ? args[args.indexOf(n) + 1] : d);
const reportDir = resolve(root, opt('--report', 'report'));
const outDir = resolve(root, opt('--out', 'public'));
const overviewSrc = join(root, 'site', 'overview');

const esc = (s) => String(s ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const features = parse(readFileSync(join(root, 'features.yml'), 'utf8')).features;
const version = readFileSync(join(repoRoot, 'pom.xml'), 'utf8').match(/<semver>([^<]+)<\/semver>/)?.[1] ?? '';
const summary = existsSync(join(reportDir, 'summary.json')) ? JSON.parse(readFileSync(join(reportDir, 'summary.json'), 'utf8')) : null;
const repoUrl = process.env.GITHUB_SERVER_URL && process.env.GITHUB_REPOSITORY
  ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}` : 'https://github.com/NASA-AMMOS/tile_service';

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });
if (existsSync(reportDir)) cpSync(reportDir, join(outDir, 'tests'), { recursive: true });

// --- overview pages (written separately under site/overview/) ---

const PAGE_CSS = `:root{--bg:#fff;--fg:#1f2328;--muted:#59636e;--line:#d1d9e0;--code:#eff2f5;--link:#0969da}
@media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#9198a1;--line:#3d444d;--code:#1f2630;--link:#4493f8}}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif}
main{max-width:900px;margin:0 auto;padding:24px 16px 48px}a{color:var(--link)}
pre,code{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;background:var(--code);border-radius:4px}pre{padding:12px;overflow-x:auto}
table{border-collapse:collapse;width:100%;display:block;overflow-x:auto}th,td{border-bottom:1px solid var(--line);padding:6px 8px;text-align:left}`;
const overviewPages = [];
if (existsSync(overviewSrc)) {
  const walk = (d) => readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)]));
  for (const f of walk(overviewSrc)) {
    const rel = relative(overviewSrc, f);
    const dest = join(outDir, 'overview', rel);
    mkdirSync(dirname(dest), { recursive: true });
    if (/\.md$/i.test(f)) {
      const md = readFileSync(f, 'utf8');
      const title = md.match(/^#\s+(.+)$/m)?.[1] ?? rel;
      const up = '../'.repeat(rel.split('/').length);
      writeFileSync(dest.replace(/\.md$/i, '.html'), `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)}</title><style>${PAGE_CSS}</style></head>
<body><main><p><a href="${up}index.html">tile_service</a></p>
${marked.parse(md.replace(/\]\(([^)]+)\.md(#[^)]*)?\)/g, (_m, p, h) => `](${p}.html${h ?? ''})`))}
</main></body></html>
`);
      overviewPages.push({ path: `overview/${rel.replace(/\.md$/i, '.html')}`, title });
    } else {
      cpSync(f, dest);
      if (/\.html?$/i.test(f)) overviewPages.push({ path: `overview/${rel}`, title: readFileSync(f, 'utf8').match(/<title>([^<]*)/)?.[1] ?? rel });
    }
  }
}
overviewPages.sort((a, b) => (a.path.endsWith('/index.html') ? -1 : b.path.endsWith('/index.html') ? 1 : a.path.localeCompare(b.path)));
const overviewHref = overviewPages[0]?.path;

// a strip of thumbnails of public products from the latest run
const heroImgs = (summary?.images ?? []).map((i) => ({ src: `tests/${i.src}`, alt: i.caption }));

// --- home page content ---

const endpoints = [
  ['GET /image_tiler?image=PATH', 'DZI descriptor (XML) for an image; starts tiling it in the background if it is not cached.'],
  ['&thumb=true / &metadata=true', 'The PNG thumbnail / the metadata.json (size, bands, histogram, stretch) of a tiled image.'],
  ['&progress=true / &error=true', 'Percent done of a running tiling / why it failed.'],
  ['&viewer=true', 'An OpenSeadragon page for the image.'],
  ['GET /image_tiler/HASH/…_proxy_image_files/L/C_R.png', 'A tile, at the URL the DZI names.'],
  ['GET /image_tiler/finder/…', 'Mars 2020 / CADRE product finder: solrange, edrs, edr, rdrs, rdr, label.'],
  ['GET /image_tiler/version, image_descriptions.xml', 'Build version; product descriptions (also the ingest health check).'],
];
const params = [
  ['stretchType, stretchLow, stretchHigh', 'percent (default), extrema, relative or none, with its limits.'],
  ['gammaMode', 'none, passthrough, convert (default), lineartosrgb, srgbtolinear.'],
  ['format, tileSize, tileOverlap, thumbHeight', 'Tile format (png, jpg) and pyramid geometry.'],
  ['overlayPreference, overlayAlpha, rdr', 'Rendering of RDR overlays (XYZ, UVW, RNG, …).'],
  ['proxy', 'http, https, s3, data or tile: which form of tile URL the DZI names.'],
  ['force, maxWaitSec, filter', 'Recompute; how long to wait before answering 202; apply mission filters.'],
];

const counts = new Map((summary?.features ?? []).map((f) => [f.id, f]));
const RESULT = { passed: ['pass', 'Passed'], failed: ['fail', 'Failed'], known: ['warn', 'Known issue'], skipped: ['', 'Skipped'] };
// a collapsed summary table of a feature's tests, each linking to its procedure
const testTable = (tests) => `<details class="tests"><summary>${tests.length} test${tests.length === 1 ? '' : 's'}</summary><div class="scroll"><table>
<thead><tr><th>Test</th><th>Scope</th><th>Result</th></tr></thead><tbody>
${tests.map((t) => {
    const [cls, label] = RESULT[t.result] ?? ['', t.result];
    return `<tr><td><a href="tests/procedures.html#${encodeURIComponent(t.anchor)}">${esc(t.title)}</a></td>`
      + `<td>${t.scopes.map((sc) => `<span class="scope">${esc(sc.slice(1))}</span>`).join(' ')}</td><td><span class="pill ${cls}">${label}</span></td></tr>`;
  }).join('\n')}
</tbody></table></div></details>`;
const capabilityRows = features.filter((f) => f.status === 'active').map((f) => {
  const c = counts.get(f.id);
  const total = c ? c.passed + c.failed + c.known : null;
  const scopes = Object.entries(c?.scopes ?? {}).map(([sc, n]) => `<span class="scope">${esc(sc.slice(1))} ${n}</span>`).join(' ');
  return `<tr>
  <td><a class="fname" href="tests/procedures.html#${encodeURIComponent(f.id)}">${esc(f.name)}</a><div class="desc">${esc(f.description.trim())}</div>${scopes ? `<div class="scopes">${scopes}</div>` : ''}${c?.tests?.length ? testTable(c.tests) : ''}</td>
  <td class="num">${total ?? '–'}</td><td class="num">${c ? c.passed : '–'}</td><td class="num ${c?.failed ? 'bad' : ''}">${c ? c.failed : '–'}</td><td class="num">${c ? c.known : '–'}</td>
</tr>`;
}).join('\n');
const planned = features.filter((f) => f.status !== 'active');

const totalTests = summary ? summary.passed + summary.failed + summary.known : 0;
const build = summary?.build && summary.build !== 'local' ? ` · build ${esc(summary.build)}` : '';
const statusLine = summary
  ? `<span class="dot ${summary.status === 'passed' ? 'ok' : 'bad'}"></span>Latest test run ${summary.status === 'passed' ? 'passed' : '<b class="bad">failed</b>'}: ${totalTests} tests, ${summary.failed} failed, ${summary.known} known issues · ${esc(summary.tier)} tier · ${esc(summary.date)}${build}${summary.commit ? ` · commit ${summary.commitUrl ? `<a href="${summary.commitUrl}">${esc(summary.commit)}</a>` : esc(summary.commit)}` : ''}`
  : 'No test results yet.';

const html = `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>tile_service</title>
<meta name="description" content="tile_service: Deep Zoom tiles, thumbnails and metadata for planetary mission images (VICAR, PDS), for the ASTRIA viewer.">
<style>
:root { --bg:#ffffff; --fg:#1f2328; --muted:#59636e; --line:#d1d9e0; --card:#f6f8fa; --link:#0969da; --accent:#0b3d91;
  --pass:#1a7f37; --fail:#d1242f; --warn:#9a6700; --code:#eff2f5; }
@media (prefers-color-scheme: dark) { :root { --bg:#0d1117; --fg:#e6edf3; --muted:#9198a1; --line:#3d444d; --card:#151b23;
  --link:#4493f8; --accent:#7aa2ff; --pass:#3fb950; --fail:#f85149; --warn:#d29922; --code:#1f2630; } }
* { box-sizing:border-box; }
html { scroll-behavior:smooth; }
body { margin:0; background:var(--bg); color:var(--fg); font:16px/1.6 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
a { color:var(--link); text-decoration:none; } a:hover { text-decoration:underline; }
header { border-bottom:1px solid var(--line); position:sticky; top:0; background:var(--bg); z-index:1; }
.wrap { max-width:1000px; margin:0 auto; padding:0 16px; }
nav { display:flex; align-items:center; justify-content:space-between; gap:12px; min-height:56px; flex-wrap:wrap; }
nav .brand { font-weight:700; letter-spacing:.04em; color:var(--fg); }
nav .links { display:flex; flex-wrap:wrap; gap:4px 18px; font-size:14px; }
.hero { padding:48px 0 8px; }
.hero h1 { font-size:36px; line-height:1.2; margin:0 0 12px; }
.hero .lead { font-size:18px; color:var(--muted); max-width:780px; margin:0; }
.actions { display:flex; flex-wrap:wrap; gap:10px; margin-top:22px; }
.button { display:inline-block; background:var(--accent); color:#fff; padding:9px 16px; border-radius:8px; font-weight:600; font-size:15px; }
.button.alt { background:transparent; color:var(--link); border:1px solid var(--line); }
.button:hover { text-decoration:none; opacity:.92; }
.status { margin-top:14px; font-size:14px; color:var(--muted); }
.dot { display:inline-block; width:9px; height:9px; border-radius:50%; margin-right:7px; background:var(--pass); }
.dot.bad { background:var(--fail); }
.strip { display:flex; gap:8px; overflow-x:auto; padding:24px 0 4px; }
.strip img { height:120px; border-radius:6px; border:1px solid var(--line); background:#000; flex:none; }
section { padding-top:8px; scroll-margin-top:64px; }
h2 { font-size:24px; margin:44px 0 12px; } h3 { font-size:16px; margin:22px 0 6px; }
p { margin:8px 0; } .muted { color:var(--muted); font-size:14px; }
.grid { display:grid; grid-template-columns:repeat(auto-fill, minmax(220px, 1fr)); gap:12px; }
.card { border:1px solid var(--line); border-radius:10px; padding:14px 16px; background:var(--card); }
.card h3 { margin:0 0 4px; font-size:15px; } .card p { margin:0; font-size:14px; color:var(--muted); }
.flow { display:flex; flex-wrap:wrap; align-items:center; gap:8px; font-size:14px; margin:10px 0; }
.flow span { border:1px solid var(--line); border-radius:8px; padding:6px 10px; background:var(--card); }
.flow b { color:var(--muted); font-weight:400; }
pre, code { font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace; }
pre { background:var(--code); border-radius:8px; padding:12px 14px; overflow-x:auto; font-size:13px; line-height:1.5; margin:6px 0 14px; }
code { background:var(--code); border-radius:4px; padding:1px 5px; font-size:13px; }
pre code { background:none; padding:0; }
.scroll { overflow-x:auto; }
table { width:100%; border-collapse:collapse; font-size:14px; }
th, td { text-align:left; padding:8px; border-bottom:1px solid var(--line); vertical-align:top; }
th { font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }
td.kw { font-family:ui-monospace,Menlo,monospace; font-size:13px; }
.num { text-align:right; font-variant-numeric:tabular-nums; width:72px; } .bad { color:var(--fail); font-weight:600; }
.fname { font-weight:600; } .desc { color:var(--muted); font-size:13px; margin-top:2px; }
.scopes { margin-top:6px; } .scope { display:inline-block; font-size:11px; padding:1px 8px; border-radius:999px; border:1px solid var(--line); background:var(--card); color:var(--muted); margin:2px 2px 0 0; }
details { border:1px solid var(--line); border-radius:8px; padding:6px 12px; margin:8px 0; }
summary { cursor:pointer; font-weight:600; padding:4px 0; }
details.tests { margin:8px 0 0; padding:2px 10px; } details.tests summary { font-size:13px; }
details.tests table { font-size:13px; } details.tests th, details.tests td { padding:5px 6px; }
details.tests td:first-child { width:60%; }
.pill { display:inline-block; font-size:12px; padding:1px 9px; border-radius:999px; white-space:nowrap; color:var(--muted); background:var(--card); }
.pill.pass { color:var(--pass); } .pill.fail { color:var(--fail); } .pill.warn { color:var(--warn); }
.results { border:1px solid var(--line); border-radius:10px; padding:16px 20px; background:var(--card); display:flex; flex-wrap:wrap;
  gap:16px; align-items:center; justify-content:space-between; }
.results p { margin:0; }
footer { border-top:1px solid var(--line); margin-top:56px; padding:20px 0 40px; color:var(--muted); font-size:13px; }
@media (max-width:600px) { .hero h1 { font-size:28px; } }
</style></head>
<body>
<header><div class="wrap"><nav><a class="brand" href="#">tile_service</a>
<span class="links">${overviewHref ? `<a href="${overviewHref}">Overview</a>` : ''}<a href="#use">How it is used</a><a href="#api">REST API</a><a href="#cli">Command line</a><a href="#testing">Testing</a><a href="tests/index.html">Test report</a><a href="${repoUrl}">GitHub</a></span></nav></div></header>
<main class="wrap">

<section class="hero">
  <h1>Deep Zoom tiles for planetary mission images</h1>
  <p class="lead">tile_service turns large VICAR and PDS images from Mars 2020, CADRE and the PDS archive into Deep Zoom (DZI) tile pyramids, with the scientific stretch and gamma handling those 12- and 16-bit products need, so a browser can pan and zoom from the whole image down to single pixels. It also serves thumbnails, histograms and a product finder. It is the imagery back end of the ASTRIA viewer, part of NASA's AMMOS Instrument Data Subsystem.</p>
  <div class="actions">${overviewHref ? `<a class="button" href="${overviewHref}">Architecture overview</a>` : ''}<a class="button alt" href="#api">REST API</a><a class="button alt" href="tests/index.html">Test report</a></div>
  <p class="status">${statusLine}</p>
  ${heroImgs.length >= 3 ? `<div class="strip">${heroImgs.slice(0, 8).map((i) => `<img src="${i.src}" alt="${esc(i.alt)}" title="${esc(i.alt)}" loading="lazy">`).join('')}</div>
  <p class="muted">Public Mars 2020 products tiled by the latest test run.</p>` : ''}
</section>

<section>
<h2>What it does</h2>
<div class="grid">
  <div class="card"><h3>Tiles big images</h3><p>VICAR, PDS, PNG and JPEG inputs become DZI pyramids (PNG or JPEG tiles) in a filesystem or S3 cache, computed once and served many times.</p></div>
  <div class="card"><h3>Stretches science data</h3><p>Histogram-percent, extrema and relative stretches map 12-bit, 16-bit and float data to 8 bits; gamma is converted or declared per product.</p></div>
  <div class="card"><h3>Renders overlays</h3><p>Stereo RDRs such as XYZ, UVW and range become colored RGBA overlays with missing data masked, using the mission image library (not available in a stub build).</p></div>
  <div class="card"><h3>Runs three ways</h3><p>A Tomcat servlet (REST API), a command-line tiler (TileCLI) for batch pre-tiling, and an AWS Lambda for S3-triggered tiling.</p></div>
</div>
</section>

<section id="use">
<h2>How it is used</h2>
<p>In ASTRIA, products arrive on an ingest volume or in S3 and are tiled ahead of time; the browser then asks for DZIs and tiles as the user pans and zooms.</p>
<div class="flow"><span>File watcher</span><b>→ NSQ →</b><span>Tiler trigger</span><b>→ <code>?image=P&amp;force=true</code> →</b><span>tile_service</span><b>→</b><span>Tile cache (fs or S3)</span></div>
<div class="flow"><span>Metadata ingester</span><b>→ <code>/finder/edr?image=P</code> →</b><span>tile_service</span><b>; ingester →</b><span>ElasticSearch</span></div>
<div class="flow"><span>ASTRIA viewer (OpenSeadragon)</span><b>→ DZI, <code>thumb</code>, <code>metadata</code> →</b><span>Reverse / auth proxy</span><b>→</b><span>tile_service</span></div>
<div class="flow"><span>S3 object created</span><b>→ SQS →</b><span>TileLambda</span><b>→</b><span>S3 tile cache</span></div>
${overviewHref ? `<p class="muted">The <a href="${overviewHref}">overview</a> describes these flows, the request lifecycle and the tiling pipeline in detail.</p>` : ''}
</section>

<section id="api">
<h2>REST API</h2>
<p>The servlet is mapped at <code>/image_tiler</code>. Image paths are relative to <code>fs_input_dir</code>, or public http(s) or S3 URLs allowed by the deployment's whitelist.</p>
<div class="scroll"><table><tbody>
${endpoints.map(([k, d]) => `<tr><td class="kw">${esc(k)}</td><td>${esc(d)}</td></tr>`).join('\n')}
</tbody></table></div>
<h3>Common parameters</h3>
<div class="scroll"><table><tbody>
${params.map(([k, d]) => `<tr><td class="kw">${esc(k)}</td><td>${esc(d)}</td></tr>`).join('\n')}
</tbody></table></div>
<p class="muted">The <a href="${repoUrl}#rest-service-mode">README</a> lists every parameter and servlet setting.</p>
</section>

<section id="cli">
<h2>Command line: TileCLI</h2>
<pre><code>mvn -P jar package                       # target/image_tiler.jar
java -jar target/image_tiler.jar -i image.IMG -fc -cl cache   # tile into ./cache/&lt;hash&gt;
java -jar target/image_tiler.jar -i image.IMG -fc -pc         # print the cache entry
java -jar target/image_tiler.jar -h                           # every option</code></pre>
<p class="muted">Version ${esc(version)}. Builds need Java 21 and Maven; <code>-P stub</code> builds against mission-image-stub, a placeholder with no mission knowledge, instead of the internal mars library (see the testing guide).</p>
</section>

<section id="testing">
<h2>Testing</h2>
<p>Every change runs a feature test suite against the real service: TileCLI and the servlet in Tomcat, on synthetic images whose expected pixels are computed and on public Mars 2020 products from the PDS Imaging Node. Tiles are decoded and checked pixel by pixel or against recorded tree hashes, including the cases of the legacy shell tests. The full suite runs on every merge to <code>main</code> and nightly and publishes the report linked here.</p>
<div class="results"><p>${statusLine}</p><span class="actions" style="margin:0"><a class="button" href="tests/procedures.html">Test procedures</a><a class="button alt" href="tests/index.html">Test report</a></span></div>
<h3>Capabilities</h3>
<p class="muted">Each capability is a feature in the test suite. Click one to read its test procedures; open its test list for a summary of every test. Known issues are tests of bugs the suite found, marked expected to fail.</p>
<div class="scroll"><table>
<thead><tr><th>Capability</th><th class="num">Tests</th><th class="num">Passed</th><th class="num">Failed</th><th class="num">Known</th></tr></thead>
<tbody>
${capabilityRows}
</tbody></table></div>
${planned.length ? `<h3>Not yet automated</h3><ul>${planned.map((f) => `<li><b>${esc(f.name)}</b>: ${esc(f.status === 'planned' ? f.milestone : f.reason)}</li>`).join('')}</ul>` : ''}
</section>
</main>
<footer><div class="wrap">tile_service is developed by the Multimission Image Processing Laboratory at JPL for NASA AMMOS.
Source, issues and the <a href="${repoUrl}/blob/main/docs/TESTING.md">testing guide</a> are on <a href="${repoUrl}">GitHub</a>.
This site and its test report are regenerated on every merge to main and nightly.</div></footer>
</body></html>
`;
writeFileSync(join(outDir, 'index.html'), html);
writeFileSync(join(outDir, '.nojekyll'), '');
console.log(`site written to ${outDir}${summary ? '' : ' (no test summary found)'}${overviewPages.length ? `, ${overviewPages.length} overview page(s)` : ''}`);
