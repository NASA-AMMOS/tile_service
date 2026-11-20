// Playwright reporter that writes the report's landing page (report/index.html):
// the build under test, a summary table of every feature's results, then every
// feature from features.yml with its description, status and a drop-down table
// of its tests, linking into the Playwright HTML report (report/playwright/).
// It also writes report/procedures.html (every test with its description, scope
// tags, result and image previews, grouped by feature), report/summary.json for
// the site, and report/overview.md for the GitHub job summary.
import { execSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import type { FullConfig, FullResult, Reporter, Suite, TestCase } from '@playwright/test/reporter';
import { E2E_ROOT, LABEL, productRevision, sourceUrl } from './env';
import { ABOUT, DEFERRED, NEEDS_REVIEW, SCOPES, features, tagOf, type Feature, type Scope } from './features';

interface Row {
  feature?: Feature;
  key: string;
  name: string;
  description: string;
  passed: number;
  failed: number;
  known: number;
  skipped: number;
  procedures: Procedure[];
  scopes: Partial<Record<Scope, number>>;
}
interface Procedure {
  title: string; group: string; testId: string; about: string; scopes: Scope[]; issues: string[];
  result: 'passed' | 'failed' | 'known' | 'skipped'; source: string; images: Picture[];
}
/** An image preview, copied to report/images/<hash>.png. */
interface Picture { src: string; caption: string; mission: boolean }
interface Item { title: string; testId: string; feature: string; issue: string; why: string; error?: string; review?: string }

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
const PW = 'playwright/index.html';
/** Product tests first, then suite bookkeeping. */
const rank = (p: Procedure) => (p.scopes.includes('@suite') ? 1 : 0);
const byRank = (ps: Procedure[]) => [...ps].sort((a, b) => rank(a) - rank(b));
const RESULT: Record<Procedure['result'], [string, string]> =
  { passed: ['pass', 'Passed'], failed: ['fail', 'Failed'], known: ['warn', 'Known issue'], skipped: ['', 'Skipped'] };
/** Anchor of a test's entry on the procedures page. */
const procedureId = (testId: string) => `t-${testId}`;
/** Anchor of a feature's row on the overview. */
const featureId = (key: string) => `f-${key}`;
/** Image previews shown per test on the procedures page; the rest are in the run details. */
const MAX_IMAGES = 8;

/** Styles shared by the overview and procedures pages. */
const CSS = `:root { --bg:#ffffff; --fg:#1f2328; --muted:#59636e; --line:#d1d9e0; --card:#f6f8fa; --link:#0969da;
  --pass:#1a7f37; --pass-bg:#dafbe1; --warn:#9a6700; --warn-bg:#fff8c5; --fail:#d1242f; --fail-bg:#ffebe9; }
@media (prefers-color-scheme: dark) { :root { --bg:#0d1117; --fg:#e6edf3; --muted:#9198a1; --line:#3d444d; --card:#151b23;
  --link:#4493f8; --pass:#3fb950; --pass-bg:#12261e; --warn:#d29922; --warn-bg:#272115; --fail:#f85149; --fail-bg:#2d1214; } }
* { box-sizing: border-box; }
body { margin:0; background:var(--bg); color:var(--fg); font:15px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Helvetica,Arial,sans-serif; }
main { max-width:1100px; margin:0 auto; padding:24px 16px 48px; }
h1 { font-size:24px; margin:0 0 4px; } h2 { font-size:18px; margin:32px 0 12px; }
a { color:var(--link); text-decoration:none; } a:hover { text-decoration:underline; }
.meta { color:var(--muted); font-size:13px; }
.banner { display:flex; flex-wrap:wrap; gap:12px; margin:20px 0 8px; }
.stat { background:var(--card); border:1px solid var(--line); border-radius:8px; padding:12px 16px; min-width:150px; }
.stat b { display:block; font-size:24px; } .stat span { color:var(--muted); font-size:13px; }
.stat.ok b { color:var(--pass); } .stat.bad b { color:var(--fail); } .stat.warn b { color:var(--warn); }
.scroll { overflow-x:auto; }
table { width:100%; border-collapse:collapse; } th, td { text-align:left; padding:10px 8px; border-bottom:1px solid var(--line); vertical-align:top; }
th { font-size:12px; text-transform:uppercase; letter-spacing:.04em; color:var(--muted); }
.num { text-align:right; font-variant-numeric:tabular-nums; width:72px; } .num.bad { color:var(--fail); font-weight:600; }
.fname { font-weight:600; } .desc { color:var(--muted); font-size:13px; margin-top:2px; }
.pill { display:inline-block; font-size:12px; padding:2px 10px; border-radius:999px; white-space:nowrap; }
.pill.pass { color:var(--pass); background:var(--pass-bg); } .pill.warn { color:var(--warn); background:var(--warn-bg); }
.pill.fail { color:var(--fail); background:var(--fail-bg); }
.pill.change { color:var(--link); border:1px solid var(--line); background:transparent; }
details { border:1px solid var(--line); border-radius:8px; padding:8px 12px; margin:8px 0; background:var(--card); }
summary { cursor:pointer; } .issue { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:13px; font-weight:600; margin-right:6px; }
.count { color:var(--muted); font-size:12px; margin-left:6px; } details ul { margin:8px 0 0; padding-left:20px; }
.fails { padding-left:20px; } .review { font-size:13px; margin:2px 0; } .err { color:var(--fail); font-family:ui-monospace,Menlo,monospace; font-size:12px; overflow-wrap:anywhere; }
.gallery { display:flex; flex-wrap:wrap; gap:8px; margin:8px 0 4px; }
.gallery a { display:flex; flex-direction:column; width:112px; color:var(--muted); font-size:11px; }
.gallery img { width:112px; height:84px; object-fit:contain; background:#000; border:1px solid var(--line); border-radius:6px; image-rendering:auto; }
.gallery span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; margin-top:2px; }
.howto { color:var(--muted); font-size:13px; margin-top:32px; border-top:1px solid var(--line); padding-top:12px; }
`;

/** Plain description text to HTML: `backticks` become code. */
const rich = (s: string) => esc(s).replace(/`([^`]+)`/g, '<code>$1</code>');

/**
 * Image previews attached by tests ("<file> preview"), copied once each to
 * report/images so the pages do not depend on the Playwright data folder.
 */
class Images {
  private copied = new Set<string>();
  private missionSeen = new Set<string>();
  /** Distinct previews of public mission products, in the order first seen. */
  readonly mission: Picture[] = [];

  constructor(private dir: string) {
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
  }

  /** A test's distinct previews (at most MAX_IMAGES), copied into the report. */
  add(attachments: { name: string; contentType: string; path?: string; body?: Buffer }[]): Picture[] {
    const out: Picture[] = [];
    for (const a of attachments) {
      const m = a.name.match(/^(.*) preview( \[mission data\])?$/);
      if (a.contentType !== 'image/png' || !m || !(a.body || (a.path && existsSync(a.path)))) continue;
      const bytes = a.body ?? readFileSync(a.path!);
      const name = `${createHash('sha256').update(bytes).digest('hex').slice(0, 16)}.png`;
      const pic = { src: `images/${name}`, caption: m[1], mission: !!m[2] };
      if (out.some((p) => p.src === pic.src) || out.length >= MAX_IMAGES) continue;
      out.push(pic);
      // a mission preview counts even when the same pixels were copied earlier
      // from a converted output, which carries no mission marker
      if (pic.mission && !this.missionSeen.has(name)) { this.missionSeen.add(name); this.mission.push(pic); }
      if (this.copied.has(name)) continue;
      this.copied.add(name);
      writeFileSync(path.join(this.dir, name), bytes);
    }
    return out;
  }
}

/** Totals over all features. */
interface Totals { passed: number; failed: number; known: number; skipped: number }

export default class OverviewReporter implements Reporter {
  private suite!: Suite;
  private project = '';
  private start = new Date();
  private outDir: string;

  constructor(options: { outputFolder?: string } = {}) {
    this.outDir = path.resolve(E2E_ROOT, options.outputFolder ?? 'report');
  }

  onBegin(config: FullConfig, suite: Suite) {
    this.suite = suite;
    this.project = suite.suites.map((s) => s.title).filter(Boolean).join(', ');
    this.start = new Date();
  }

  async onEnd(result: FullResult) {
    const reg = features();
    const rows = new Map<string, Row>();
    const empty = { passed: 0, failed: 0, known: 0, skipped: 0, procedures: [], scopes: {} };
    for (const f of reg.filter((x) => x.status === 'active')) {
      rows.set(f.id, { feature: f, key: f.id, name: f.name, description: f.description.trim(), ...empty, procedures: [], scopes: {} });
    }
    const infra = (key: string, name: string, description: string) =>
      rows.get(key) ?? rows.set(key, { key, name, description, ...empty, procedures: [], scopes: {} }).get(key)!;
    const images = new Images(path.join(this.outDir, 'images'));
    const failures: Item[] = [];
    const known: Item[] = [];

    for (const t of this.suite.allTests()) {
      const row = this.rowFor(t, reg, rows, infra);
      const last = t.results.at(-1);
      const outcome = t.outcome();
      const issueNote = [...t.annotations, ...(last?.annotations ?? [])].find((a) => a.type === 'issue')?.description ?? '';
      const [issue, ...why] = issueNote.split(': ');
      const review = [...t.annotations, ...(last?.annotations ?? [])].find((a) => a.type === NEEDS_REVIEW)?.description;
      const item = { title: t.titlePath().slice(3).join(' › '), testId: t.id, feature: row.name, issue: issue || '', why: why.join(': '), review };
      let result: Procedure['result'] = 'passed';
      if (outcome === 'skipped') { row.skipped++; result = 'skipped'; }
      else if (outcome === 'unexpected' || outcome === 'flaky') {
        row.failed++;
        result = 'failed';
        failures.push({ ...item, error: (last?.error?.message ?? '').replace(/\u001b\[[0-9;]*m/g, '').split('\n')[0] });
      } else if (last && (last.status === 'failed' || last.status === 'timedOut')) {
        row.known++;
        result = 'known';
        known.push(item);
      } else row.passed++;
      const scopes = t.tags.filter((tag): tag is Scope => tag in SCOPES);
      for (const sc of scopes) row.scopes[sc] = (row.scopes[sc] ?? 0) + 1;
      row.procedures.push({
        title: t.title, group: t.parent.title, testId: t.id, scopes, result,
        images: images.add(last?.attachments ?? []),
        about: t.annotations.find((a) => a.type === ABOUT)?.description ?? '',
        issues: [...t.annotations, ...(last?.annotations ?? [])].filter((a) => a.type === 'issue' || a.type === NEEDS_REVIEW || a.type === DEFERRED)
          .map((a) => a.type === NEEDS_REVIEW ? `NEEDS REVIEW: ${a.description ?? ''}`
            : a.type === DEFERRED ? `DEFERRED: ${a.description ?? ''}` : a.description ?? '')
          .filter((v, i, a) => a.indexOf(v) === i && !a.includes(`NEEDS REVIEW: ${v}`)),
        source: t.annotations.find((a) => a.type === 'junit source')?.description ?? sourceUrl(t.location.file, t.location.line),
      });
    }

    const all = [...rows.values()];
    const totals = all.reduce((a, r) => ({ passed: a.passed + r.passed, failed: a.failed + r.failed, known: a.known + r.known,
      skipped: a.skipped + r.skipped }), { passed: 0, failed: 0, known: 0, skipped: 0 });
    const notAutomated = reg.filter((f) => f.status !== 'active');
    const info = this.runInfo(result);

    mkdirSync(this.outDir, { recursive: true });
    writeFileSync(path.join(this.outDir, 'summary.json'), JSON.stringify({
      status: result.status, ...totals, ...info,
      // previews of public mission products, for the site's image strip
      images: images.mission.slice(0, 12),
      features: all.map((r) => ({ id: r.key, name: r.name, passed: r.passed, failed: r.failed, known: r.known, skipped: r.skipped,
        scopes: r.scopes,
        tests: byRank(r.procedures).map((p) => ({ title: p.title, anchor: procedureId(p.testId), result: p.result, scopes: p.scopes })) })),
    }, null, 2));
    writeFileSync(path.join(this.outDir, 'index.html'), this.html(all, totals, failures, known, notAutomated, info, result));
    writeFileSync(path.join(this.outDir, 'procedures.html'), this.procedures(all, info, result));
    writeFileSync(path.join(this.outDir, 'overview.md'), this.markdown(all, totals, failures, known, notAutomated, info, result));
  }

  private rowFor(t: TestCase, reg: Feature[], rows: Map<string, Row>,
    infra: (k: string, n: string, d: string) => Row): Row {
    const f = reg.find((x) => t.tags.includes(tagOf(x)));
    if (f && rows.has(f.id)) return rows.get(f.id)!;
    if (t.tags.includes('@registry')) {
      return infra('registry', 'Test suite integrity', 'Feature registry, specs and test-data manifest are consistent.');
    }
    return infra('unassigned', 'Tests not assigned to a feature', 'Tests whose describe() does not use feature(); registry.spec.ts fails on them.');
  }

  private runInfo(result: FullResult) {
    let commit = process.env.GITHUB_SHA ?? '';
    if (!commit) {
      try { commit = execSync('git rev-parse HEAD', { cwd: E2E_ROOT, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim(); } catch { /* not a checkout */ }
    }
    const server = process.env.GITHUB_SERVER_URL;
    const repo = process.env.GITHUB_REPOSITORY;
    const runId = process.env.GITHUB_RUN_ID;
    return {
      build: LABEL,
      revision: productRevision(),
      tier: this.project || 'all',
      date: this.start.toISOString().replace('T', ' ').slice(0, 16) + ' UTC',
      duration: `${Math.round(result.duration / 1000)} s`,
      commit: commit.slice(0, 7),
      commitUrl: server && repo && commit ? `${server}/${repo}/commit/${commit}` : '',
      runUrl: server && repo && runId ? `${server}/${repo}/actions/runs/${runId}` : '',
    };
  }

  private html(rows: Row[], totals: Totals, failures: Item[], known: Item[],
    notAutomated: Feature[], info: ReturnType<OverviewReporter['runInfo']>, result: FullResult): string {
    const ok = result.status === 'passed';
    const testLink = (i: Item) => `<a href="${PW}#?testId=${encodeURIComponent(i.testId)}">${esc(i.title)}</a>`;
    const n = (v: number, cls = '') => `<td class="num ${v ? cls : ''}">${v}</td>`;
    const summaryRows = rows.map((r) => `<tr><td><a href="#${esc(featureId(r.key))}">${esc(r.name)}</a></td>`
      + `${n(r.procedures.length)}${n(r.passed)}${n(r.failed, 'bad')}${n(r.known, 'warn')}${n(r.skipped)}</tr>`).join('\n');
    const all = rows.reduce((a, r) => a + r.procedures.length, 0);

    const featureRows = rows.map((r) => {
      const state = r.failed ? 'fail' : r.known ? 'warn' : 'pass';
      const label = r.failed ? 'Failing' : r.known ? 'Passing, with known issues' : 'Passing';
      const link = r.feature ? `${PW}#?q=${encodeURIComponent(tagOf(r.feature))}` : `${PW}#?q=${encodeURIComponent('@' + r.key)}`;
      // a collapsed summary table of the feature's tests, each linking to its procedure
      const tests = r.procedures.length ? `<details class="tests"><summary>${r.procedures.length} test${r.procedures.length === 1 ? '' : 's'}</summary><div class="scroll"><table>
<thead><tr><th>Test</th><th>Scope</th><th>Result</th></tr></thead><tbody>
${byRank(r.procedures).map((p) => `<tr><td><a href="procedures.html#${esc(procedureId(p.testId))}">${esc(p.title)}</a></td>`
        + `<td>${p.scopes.map((sc) => `<span class="scope">${esc(sc.slice(1))}</span>`).join(' ')}</td>`
        + `<td><span class="pill ${RESULT[p.result][0]}">${RESULT[p.result][1]}</span></td></tr>`).join('\n')}
</tbody></table></div><p class="more"><a href="procedures.html#${esc(r.key)}">Detailed procedures</a></p></details>` : '';
      return `<tr id="${esc(featureId(r.key))}">
  <td><a class="fname" href="${link}">${esc(r.name)}</a><div class="desc">${esc(r.description)}</div>${tests}</td>
  <td><span class="pill ${state}">${label}</span></td>
</tr>`;
    }).join('\n');

    const byIssue = new Map<string, Item[]>();
    for (const k of known) byIssue.set(k.issue, [...(byIssue.get(k.issue) ?? []), k]);
    const knownHtml = [...byIssue].sort(([a], [b]) => a.localeCompare(b, undefined, { numeric: true })).map(([issue, items]) => `
<details><summary><span class="issue">${esc(issue || 'untracked')}</span> ${items[0].review ? '<span class="pill warn">needs review</span> ' : ''}${esc(items[0].why)} <span class="count">${items.length} test${items.length > 1 ? 's' : ''}</span></summary>
<ul>${items.map((i) => `<li>${esc(i.feature)}: ${testLink(i)}</li>`).join('')}</ul></details>`).join('\n');

    const failHtml = failures.length ? `<section><h2>Failures</h2><ul class="fails">${failures.map((f) =>
      `<li><strong>${esc(f.feature)}</strong>: ${testLink(f)}${f.review ? `<div class="review">Needs review (bug or intended?): ${esc(f.review)}</div>` : ''}<div class="err">${esc(f.error ?? '')}</div></li>`).join('')}</ul></section>` : '';

    const notAutoHtml = notAutomated.length ? `<section><h2>Not yet automated</h2><table>
<thead><tr><th>Feature</th><th>Status</th></tr></thead><tbody>
${notAutomated.map((f) => `<tr><td><span class="fname">${esc(f.name)}</span><div class="desc">${esc(f.description.trim())}</div></td>
<td>${f.status === 'planned' ? `Planned (${esc(f.milestone ?? '')})` : `Excluded: ${esc((f.reason ?? '').trim())}`}</td></tr>`).join('\n')}
</tbody></table></section>` : '';

    const meta = [
      `Build under test: <strong>${esc(info.build)}</strong>${info.revision ? ` (${esc(info.revision)})` : ''}`,
      `Tier: <strong>${esc(info.tier)}</strong>`, esc(info.date), `Duration ${esc(info.duration)}`,
      info.commit ? (info.commitUrl ? `Commit <a href="${info.commitUrl}">${info.commit}</a>` : `Commit ${info.commit}`) : '',
      info.runUrl ? `<a href="${info.runUrl}">Workflow run</a>` : '',
    ].filter(Boolean).join(' · ');

    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>tile_service Feature Tests</title>
<style>
${CSS}
.result { margin:16px 0 0; font-size:15px; }
.summary td, .summary th { padding:6px 8px; } .summary tfoot td { font-weight:700; border-top:2px solid var(--line); border-bottom:0; }
.summary .num { width:64px; } .num.warn { color:var(--warn); font-weight:600; } .num.chg { color:var(--link); font-weight:600; }
code { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12.5px; }
details.tests { background:transparent; padding:2px 10px; } details.tests summary { font-size:13px; font-weight:600; }
details.tests table { font-size:13px; } details.tests th, details.tests td { padding:5px 6px; } details.tests td:first-child { width:55%; }
details.tests .scope { display:inline-block; font-size:11px; padding:1px 8px; border-radius:999px; border:1px solid var(--line); background:var(--card); color:var(--muted); margin:1px 2px 1px 0; }
details.tests .more { font-size:13px; margin:8px 0 4px; }
</style></head>
<body><main>
<h1>tile_service feature tests</h1>
<div class="meta">${meta}</div>
<p class="result">Result: <span class="pill ${ok ? 'pass' : 'fail'}">${ok ? 'Passed' : 'Failed'}</span> <span class="meta">${totals.passed} passed, ${totals.failed} unexpected failures. <a href="procedures.html">Test procedures</a>: what every test does and checks. <a href="${PW}">Full Playwright report</a>: commands, logs and images.</span></p>
<section><h2>Summary</h2><div class="scroll"><table class="summary">
<thead><tr><th>Feature</th><th class="num">Tests</th><th class="num">Passed</th><th class="num">Failed</th><th class="num">Known issues</th><th class="num">Skipped</th></tr></thead>
<tbody>
${summaryRows}
</tbody>
<tfoot><tr><td>Total</td>${n(all)}${n(totals.passed)}${n(totals.failed, 'bad')}${n(totals.known, 'warn')}${n(totals.skipped)}</tr></tfoot>
</table></div>
<p class="meta">Known issues are tests of tracked bugs, marked "expected to fail".</p></section>
${failHtml}
<section><h2>Features</h2><div class="scroll"><table>
<thead><tr><th>Feature</th><th>Status</th></tr></thead>
<tbody>
${featureRows}
</tbody></table></div></section>
${known.length ? `<section><h2>Known issues</h2><p class="meta">Bugs reproduced by the tests and tracked by issue or code location. Each test is marked "expected to fail" and turns red once the bug is fixed, so the marker is removed with the fix. Those marked "needs review" were found by this suite and have no ticket yet: decide bug (ticket it) or intended (correct the test).</p>${knownHtml}</section>` : ''}
${notAutoHtml}
<p class="howto">Click a feature to see its tests in the <a href="${PW}">full Playwright report</a>, with the commands, HTTP exchanges, logs and thumbnails of every test. Open its test list for a summary of each test, linked to its detailed procedure.</p>
</main></body></html>
`;
  }

  private procedures(rows: Row[], info: ReturnType<OverviewReporter['runInfo']>, result: FullResult): string {
    const chip = (sc: Scope) => `<span class="scope" title="${esc(SCOPES[sc])}">${esc(sc.slice(1))}</span>`;
    const counts = (m: Partial<Record<Scope, number>>) => (Object.keys(SCOPES) as Scope[]).filter((sc) => m[sc])
      .map((sc) => `${chip(sc)}<span class="n">${m[sc]}</span>`).join(' ');
    const total: Partial<Record<Scope, number>> = {};
    for (const r of rows) for (const [sc, n] of Object.entries(r.scopes) as [Scope, number][]) total[sc] = (total[sc] ?? 0) + n;
    const legend = (Object.keys(SCOPES) as Scope[]).filter((sc) => total[sc]).map((sc) =>
      `<li><button class="scope" data-scope="${esc(sc.slice(1))}">${esc(sc.slice(1))}</button> <span class="n">${total[sc]}</span> ${esc(SCOPES[sc].replace(/^[^:]*: /, ''))}</li>`).join('\n');
    const sections = rows.filter((r) => r.procedures.length).map((r) => `
<section id="${esc(r.key)}" class="feature">
<h2>${esc(r.name)}</h2>
<p class="desc">${esc(r.description)}</p>
<p class="counts">${r.procedures.length} tests · ${counts(r.scopes)}</p>
<ol class="procs">
${byRank(r.procedures).map((p) => {
      const [cls, label] = RESULT[p.result];
      const group = p.group && p.group !== r.name ? `<span class="group">${esc(p.group)}</span>` : '';
      const run = `${PW}#?testId=${encodeURIComponent(p.testId)}`;
      const shots = p.images.length ? `<div class="gallery">${p.images.map((i) =>
        `<a href="${run}" title="${esc(i.caption)}"><img src="${i.src}" alt="${esc(i.caption)}" loading="lazy"><span>${esc(i.caption)}</span></a>`).join('')}</div>` : '';
      return `<li id="${esc(procedureId(p.testId))}" data-scopes="${esc(p.scopes.map((sc) => sc.slice(1)).join(' '))}">
  <div class="head"><span class="ptitle">${esc(p.title)}</span><span><span class="pill ${cls}">${label}</span></span></div>
  <div class="chips">${p.scopes.map(chip).join('')}${group}</div>
  <p>${p.about ? rich(p.about) : '<em>No description.</em>'}</p>
  ${shots}
  ${p.issues.map((i) => `<p class="note">${i.startsWith('NOTE: ') ? `Note: ${rich(i.slice(6))}` : i.startsWith('NEEDS REVIEW: ') ? `Needs review (bug or intended?): ${rich(i.slice(14))}` : `Issue ${rich(i)}`}</p>`).join('')}
  <p class="links"><a href="${run}">Run details</a> · <a href="${esc(p.source)}">Source</a></p>
</li>`;
    }).join('\n')}
</ol>
</section>`).join('\n');
    const toc = rows.filter((r) => r.procedures.length).map((r) => `<li><a href="#${esc(r.key)}">${esc(r.name)}</a> <span class="n">${r.procedures.length}</span></li>`).join('');
    return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>tile_service Test Procedures</title>
<style>
${CSS}
code { font-family:ui-monospace,SFMono-Regular,Menlo,monospace; font-size:12.5px; background:var(--card); border:1px solid var(--line); border-radius:4px; padding:0 4px; }
.scope { display:inline-block; font-size:11px; padding:1px 8px; border-radius:999px; border:1px solid var(--line); background:var(--card); color:var(--fg); margin-right:4px; font-family:inherit; }
button.scope { cursor:pointer; font-size:12px; } button.scope.on { background:var(--link); color:#fff; border-color:var(--link); }
.n { color:var(--muted); font-size:12px; margin-right:8px; font-variant-numeric:tabular-nums; }
.legend { list-style:none; padding:0; display:grid; grid-template-columns:repeat(auto-fill, minmax(300px, 1fr)); gap:6px 16px; font-size:13px; color:var(--muted); }
.toc { columns:2 260px; padding-left:20px; font-size:14px; }
.feature { border-top:1px solid var(--line); margin-top:28px; } .counts { font-size:13px; }
.procs { padding-left:22px; } .procs li { margin:0 0 16px; padding-left:4px; } .procs p { margin:4px 0; font-size:14px; }
.head { display:flex; flex-wrap:wrap; gap:8px; align-items:baseline; justify-content:space-between; }
.ptitle { font-weight:600; } .chips { margin-top:3px; } .group, .when { color:var(--muted); font-size:12px; margin-right:8px; }
.note { color:var(--muted); } .links { font-size:12px; } .bad { color:var(--fail); }
.procs li.hidden, .feature.hidden { display:none; }
</style></head>
<body><main>
<p class="meta"><a href="index.html">← Feature overview</a></p>
<h1>tile_service test procedures</h1>
<div class="meta">build ${esc(info.build)}${info.revision ? ` (${esc(info.revision)})` : ''} · ${esc(info.tier)} tier · ${esc(info.date)}${info.commit ? ` · commit ${info.commitUrl ? `<a href="${info.commitUrl}">${info.commit}</a>` : info.commit}` : ''} · ${result.status === 'passed' ? 'passed' : '<b class="bad">failed</b>'}</div>
<p>Every test in the suite, grouped by feature: what it does, what makes it pass, the kinds of testing it covers, its latest result and the thumbnails of what it tiled. Each test links to its run details (commands, HTTP exchanges and logs) and to its source on GitHub.</p>
<h2>Scope of testing</h2>
<p class="meta">Each test carries one or more scope tags. Click a tag to show only those tests.</p>
<ul class="legend">
${legend}
</ul>
<h2>Features</h2>
<ul class="toc">${toc}</ul>
${sections}
</main>
<script>
const buttons = [...document.querySelectorAll('button.scope')];
buttons.forEach((b) => b.addEventListener('click', () => {
  const on = !b.classList.contains('on');
  buttons.forEach((x) => x.classList.remove('on'));
  if (on) b.classList.add('on');
  const want = on ? b.dataset.scope : null;
  document.querySelectorAll('.procs li').forEach((li) => li.classList.toggle('hidden', !!want && !li.dataset.scopes.split(' ').includes(want)));
  document.querySelectorAll('.feature').forEach((f) => f.classList.toggle('hidden', !f.querySelector('.procs li:not(.hidden)')));
}));
</script>
</body></html>
`;
  }

  private markdown(rows: Row[], totals: Totals, failures: Item[], known: Item[],
    notAutomated: Feature[], info: ReturnType<OverviewReporter['runInfo']>, result: FullResult): string {
    const out = [`### tile_service feature tests: ${result.status === 'passed' ? 'passed' : '**failed**'}`, '',
      `Build **${info.build}**${info.revision ? ` (${info.revision})` : ''}: ${totals.passed} passed, ${totals.failed} failed, ${totals.known} known issues (${info.tier} tier, ${info.duration})`, '',
      '| Feature | Passed | Failed | Known issues |', '|---|---:|---:|---:|',
      ...rows.map((r) => `| ${r.name} | ${r.passed} | ${r.failed ? `**${r.failed}**` : 0} | ${r.known} |`),
      `| **Total** | ${totals.passed} | ${totals.failed ? `**${totals.failed}**` : 0} | ${totals.known} |`];
    if (failures.length) out.push('', '**Failures**', '', ...failures.map((f) => `- ${f.feature}: ${f.title}: ${f.review ? `needs review (${f.review}): ` : ''}${f.error ?? ''}`));
    if (known.length) {
      const issues = [...new Set(known.map((k) => `${k.review ? '(needs review) ' : ''}${k.issue}: ${k.why}`))].sort((a, b) => a.localeCompare(b, undefined, { numeric: true }));
      out.push('', `<details><summary>Known issues (${issues.length})</summary>`, '', ...issues.map((i) => `- ${i}`), '', '</details>');
    }
    if (notAutomated.length) {
      out.push('', `Not yet automated: ${notAutomated.map((f) => `${f.name} (${f.status === 'planned' ? f.milestone : 'excluded'})`).join(', ')}.`);
    }
    out.push('', `Full report: the \`feature-report-${info.tier}\` artifact of this run (kept 5 days); open \`report/index.html\`.`);
    return out.join('\n') + '\n';
  }
}
