// The servlet under test: a Tomcat instance started by global-setup.ts with the
// war deployed as /image_tiler, and the helpers specs use to talk to it.
//
// Layout under .cache/run/<label>/ (one per TILE_SERVICE_LABEL):
//   tomcat/        CATALINA_BASE (server.xml, unpacked webapp)
//   srv/           Tomcat's working directory. The localhost-fscache web.xml uses
//                  relative paths: fs_input_dir=test, fs_cache_loc=test/cache,
//                  lru_disk_cache_dir=tmp, so inputs live in srv/test.
//   srv/outside/   files outside fs_input_dir, for the path traversal tests
//   cli/           working directory of TileCLI runs (same test/ layout)
//   catalina.out   the servlet's stdout and stderr (the service log)
import { spawn, execFileSync } from 'node:child_process';
import { closeSync, copyFileSync, existsSync, mkdirSync, openSync, readFileSync, readdirSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs';
import http from 'node:http';
import path from 'node:path';
import type { APIRequestContext, APIResponse, TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';
import { manifestAssets } from './data';
import { CACHE, DATA_DIR, FINDER_ZIP, JACOCO_OPT, JAVA, PORT, RUN_DIR, SERVER_XMX, SYNTH_DIR, productWar } from './env';
import { vicarBytes, type VicarSpec } from './synthetic';
import { parseDzi, type Dzi } from './tree';

export interface ServerInfo { baseUrl: string; relaxedUrl: string; log: string; pid: number; srvDir: string; cliDir: string; war: string }

const INFO = path.join(RUN_DIR, 'server.json');
export const SRV_DIR = path.join(RUN_DIR, 'srv');
export const CLI_DIR = path.join(RUN_DIR, 'cli');
export const OUTSIDE_DIR = path.join(SRV_DIR, 'outside');
/** A VICAR file outside fs_input_dir whose label carries SECRET_MARKER, and a copy under a Mars 2020 EDR name. */
export const OUTSIDE_SECRET = 'secret.vic';
export const OUTSIDE_EDR = 'ZL0_0051_0671470568_053EBY_N0031950ZCAM03111_0340LUJ01.IMG';
export const SECRET_MARKER = 'OUTSIDE-FS-INPUT-DIR';
const SECRET_SPEC: VicarSpec = { nl: 4, ns: 8, nb: 1, format: 'BYTE', linc: 1, sinc: 1, binc: 0,
  label: `PROPERTY='SECRET'  NOTE='${SECRET_MARKER}'  ` };
const TOMCAT_BASE = path.join(RUN_DIR, 'tomcat');
const LOG = path.join(RUN_DIR, 'catalina.out');

let info: ServerInfo | undefined;
export function server(): ServerInfo {
  info ??= JSON.parse(readFileSync(INFO, 'utf8'));
  return info!;
}

/** URL of a path under the webapp, e.g. tilerUrl('?image=x.IMG') or tilerUrl('/version'). */
export const tilerUrl = (p: string, base = server().baseUrl) => `${base}/image_tiler${p.startsWith('?') || p.startsWith('/') || !p ? '' : '/'}${p}`;

// --- setup (global-setup.ts) ---

/** Unpacks the pinned Tomcat once into .cache/tomcat and returns CATALINA_HOME. */
function tomcatHome(): string {
  const a = manifestAssets().find((x) => x.id === 'tomcat-9')!;
  const home = path.join(CACHE, 'tomcat', a.file.replace(/\.zip$/, ''));
  if (!existsSync(path.join(home, 'bin', 'bootstrap.jar'))) {
    mkdirSync(path.dirname(home), { recursive: true });
    execFileSync('unzip', ['-q', '-o', path.join(DATA_DIR, a.file), '-d', path.dirname(home)]);
  }
  return home;
}

/** The finder test tree (test/finder-data.zip), unpacked once into .cache/finder-data. */
function finderData(): string {
  const dir = path.join(CACHE, 'finder-data');
  const stamp = path.join(dir, '.zip-mtime');
  const mtime = String(statSync(FINDER_ZIP).mtimeMs);
  if (!existsSync(stamp) || readFileSync(stamp, 'utf8') !== mtime) {
    rmSync(dir, { recursive: true, force: true });
    mkdirSync(dir, { recursive: true });
    execFileSync('unzip', ['-q', '-o', FINDER_ZIP, '-d', dir]);
    writeFileSync(stamp, mtime);
  }
  return dir;
}

/** A test/ input tree: public products and synthetic inputs by link, the finder sols, an empty cache. */
function inputTree(root: string) {
  rmSync(root, { recursive: true, force: true });
  const test = path.join(root, 'test');
  mkdirSync(path.join(test, 'cache'), { recursive: true });
  mkdirSync(path.join(root, 'tmp'), { recursive: true });
  // test/<product> for the legacy cases (they hash whole cache entries, so nothing
  // else may add variants to them) and test/alt/<product> for every other test
  mkdirSync(path.join(test, 'alt'));
  for (const a of manifestAssets().filter((x) => /\.IMG$/i.test(x.file))) {
    if (!existsSync(path.join(DATA_DIR, a.file))) continue;
    symlinkSync(path.join(DATA_DIR, a.file), path.join(test, a.file));
    symlinkSync(path.join(DATA_DIR, a.file), path.join(test, 'alt', a.file));
  }
  symlinkSync(SYNTH_DIR, path.join(test, 'synthetic'));
  const finder = finderData();
  for (const sol of readdirSync(finder).filter((f) => /^\d+$/.test(f))) symlinkSync(path.join(finder, sol), path.join(test, sol));
}

function serverXml(port: number) {
  // A second connector accepts the characters stock Tomcat rejects in a query
  // (as some proxied deployments configure it), for the reflected-XSS tests.
  return `<?xml version="1.0" encoding="UTF-8"?>
<Server port="-1" shutdown="SHUTDOWN">
  <Service name="Catalina">
    <Connector port="${port}" address="127.0.0.1" protocol="HTTP/1.1" connectionTimeout="20000" />
    <Connector port="${port + 1}" address="127.0.0.1" protocol="HTTP/1.1" connectionTimeout="20000"
               relaxedQueryChars="&quot;&lt;&gt;[\\]^\`{|}" />
    <Engine name="Catalina" defaultHost="localhost">
      <Host name="localhost" appBase="webapps" unpackWARs="false" autoDeploy="false" />
    </Engine>
  </Service>
</Server>
`;
}

async function ping(url: string): Promise<number> {
  return new Promise((resolve) => {
    const req = http.get(url, (res) => { res.resume(); resolve(res.statusCode ?? 0); });
    req.on('error', () => resolve(0));
    req.setTimeout(5000, () => { req.destroy(); resolve(0); });
  });
}

function alive(pid: number) {
  try { process.kill(pid, 0); return true; } catch { return false; }
}

export async function stopServer(pid?: number) {
  pid ??= existsSync(INFO) ? (JSON.parse(readFileSync(INFO, 'utf8')) as ServerInfo).pid : undefined;
  if (!pid || !alive(pid)) return;
  process.kill(pid, 'SIGTERM'); // a normal Tomcat shutdown, so a JaCoCo agent writes its data
  for (let i = 0; i < 100 && alive(pid); i++) await new Promise((r) => setTimeout(r, 200));
  if (alive(pid)) process.kill(pid, 'SIGKILL');
}

/** Lays out .cache/run/<label>, starts Tomcat with the war and waits for /image_tiler/version. */
export async function startServer(): Promise<ServerInfo> {
  await stopServer();
  const war = productWar();
  const home = tomcatHome();
  for (const p of [PORT, PORT + 1]) {
    if (await ping(`http://127.0.0.1:${p}/`)) throw new Error(`port ${p} is in use; set TILE_SERVICE_PORT to a free port pair`);
  }
  rmSync(TOMCAT_BASE, { recursive: true, force: true });
  for (const d of ['conf', 'logs', 'temp', 'work', 'webapps/image_tiler']) mkdirSync(path.join(TOMCAT_BASE, d), { recursive: true });
  for (const f of ['web.xml', 'logging.properties', 'catalina.properties', 'context.xml']) copyFileSync(path.join(home, 'conf', f), path.join(TOMCAT_BASE, 'conf', f));
  writeFileSync(path.join(TOMCAT_BASE, 'conf', 'server.xml'), serverXml(PORT));
  execFileSync('unzip', ['-q', war, '-d', path.join(TOMCAT_BASE, 'webapps', 'image_tiler')]);

  inputTree(SRV_DIR);
  inputTree(CLI_DIR);
  // readable files next to fs_input_dir, which no request may reach
  mkdirSync(OUTSIDE_DIR, { recursive: true });
  writeFileSync(path.join(OUTSIDE_DIR, OUTSIDE_SECRET), vicarBytes(SECRET_SPEC));
  writeFileSync(path.join(OUTSIDE_DIR, OUTSIDE_EDR), vicarBytes(SECRET_SPEC));

  const out = openSync(LOG, 'w');
  const cp = [path.join(home, 'bin', 'bootstrap.jar'), path.join(home, 'bin', 'tomcat-juli.jar')].join(path.delimiter);
  const child = spawn(JAVA, [
    ...JACOCO_OPT, `-Xmx${SERVER_XMX}`, '-Djava.awt.headless=true',
    // what catalina.sh adds on Java 9+
    '--add-opens=java.base/java.lang=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED',
    '--add-opens=java.base/java.util=ALL-UNNAMED', '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
    '--add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED',
    `-Djava.util.logging.config.file=${path.join(TOMCAT_BASE, 'conf', 'logging.properties')}`,
    '-Djava.util.logging.manager=org.apache.juli.ClassLoaderLogManager',
    `-Dcatalina.home=${home}`, `-Dcatalina.base=${TOMCAT_BASE}`, `-Djava.io.tmpdir=${path.join(TOMCAT_BASE, 'temp')}`,
    '-cp', cp, 'org.apache.catalina.startup.Bootstrap', 'start',
  ], { cwd: SRV_DIR, stdio: ['ignore', out, out], detached: true });
  child.unref();
  closeSync(out);

  const s: ServerInfo = {
    baseUrl: `http://127.0.0.1:${PORT}`, relaxedUrl: `http://127.0.0.1:${PORT + 1}`, log: LOG, pid: child.pid!,
    srvDir: SRV_DIR, cliDir: CLI_DIR, war,
  };
  writeFileSync(INFO, JSON.stringify(s, null, 2));
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    if (await ping(`${s.baseUrl}/image_tiler/version`) === 200) return s;
    if (!alive(s.pid)) break;
    await new Promise((r) => setTimeout(r, 500));
  }
  const tail = existsSync(LOG) ? readFileSync(LOG, 'utf8').split('\n').slice(-40).join('\n') : '';
  await stopServer(s.pid);
  throw new Error(`image_tiler did not start on ${s.baseUrl} (log ${LOG}):\n${tail}`);
}

// --- helpers for specs ---

/** Current length of the service log; pass it to logSince() to read what a test caused. */
export const logOffset = () => (existsSync(server().log) ? statSync(server().log).size : 0);
export function logSince(offset: number): string {
  const buf = readFileSync(server().log);
  return buf.subarray(offset).toString('utf8');
}

/** HTTP client for the servlet that records every exchange and attaches it as http.log. */
export class Tiler {
  private lines: string[] = [];
  constructor(readonly request: APIRequestContext, private testInfo: TestInfo) {}

  private note(method: string, url: string, r: { status(): number; headers(): Record<string, string> }, body?: string) {
    const ct = r.headers()['content-type'] ?? '';
    this.lines.push(`${method} ${url}\n  -> ${r.status()} ${ct}${body !== undefined ? `\n  ${body.slice(0, 600).replace(/\n/g, '\n  ')}` : ''}`);
  }

  async get(p: string, opts: { base?: string; quiet?: boolean } = {}): Promise<APIResponse> {
    const url = tilerUrl(p, opts.base);
    const r = await this.request.get(url, { maxRedirects: 0, failOnStatusCode: false, timeout: 120_000 });
    const textual = /text|json|xml|html/.test(r.headers()['content-type'] ?? '');
    if (!opts.quiet) this.note('GET', url, r, textual ? await r.text() : undefined);
    return r;
  }

  async post(p: string, data: unknown, contentType = 'application/json'): Promise<APIResponse> {
    const url = tilerUrl(p);
    const body = typeof data === 'string' ? data : JSON.stringify(data);
    const r = await this.request.post(url, { data: body, headers: { 'Content-Type': contentType }, failOnStatusCode: false, maxRedirects: 0 });
    this.note(`POST (${body})`, url, r, await r.text());
    return r;
  }

  /** A request target sent byte for byte (no client-side encoding), for reflected-content tests. */
  async raw(target: string, base = server().baseUrl): Promise<{ status: number; body: string }> {
    const u = new URL(base);
    const res = await new Promise<{ status: number; body: string }>((resolve, reject) => {
      const req = http.request({ host: u.hostname, port: u.port, method: 'GET', path: `/image_tiler${target}` }, (r) => {
        const chunks: Buffer[] = [];
        r.on('data', (c) => chunks.push(c));
        r.on('end', () => resolve({ status: r.statusCode ?? 0, body: Buffer.concat(chunks).toString('utf8') }));
      });
      req.on('error', reject);
      req.end();
    });
    this.lines.push(`GET (raw) ${base}/image_tiler${target}\n  -> ${res.status}\n  ${res.body.slice(0, 300).replace(/\n/g, '\n  ')}`);
    return res;
  }

  /** Polls `&progress=true` until the tiling reports 100%. */
  async waitDone(query: string, timeoutMs = 240_000) {
    const deadline = Date.now() + timeoutMs;
    let last = '';
    while (Date.now() < deadline) {
      const r = await this.get(`?${query}&progress=true`, { quiet: true });
      last = `${r.status()} ${(await r.text()).trim()}`;
      if (r.status() === 200 && last.startsWith('200 100')) { this.lines.push(`  progress ${query}: ${last}`); return; }
      const e = await this.get(`?${query}&error=true`, { quiet: true });
      if (e.status() === 200) throw new Error(`tiling ${query} failed: ${(await e.text()).trim()}`);
      await new Promise((r) => setTimeout(r, 300));
    }
    throw new Error(`tiling ${query} did not finish in ${timeoutMs / 1000}s (last progress: ${last})`);
  }

  /** Requests the DZI for a query (`image=...&...`), waits for the tiling and returns its descriptor and cache directory. */
  async tile(query: string): Promise<{ dzi: Dzi; xml: string; dir: string; cachePath: string }> {
    const r = await this.get(`?${query}`);
    expect(r.status(), `DZI for ${query}`).toBe(200);
    expect(r.headers()['content-type'] ?? '').toMatch(/xml/);
    const xml = await r.text();
    await this.waitDone(query);
    const dzi = parseDzi(xml);
    const cachePath = cachePathOf(dzi);
    const dir = path.join(SRV_DIR, 'test', 'cache', cachePath);
    // progress reaches 100 (and the task serves its metadata from memory) just before
    // the last files reach the cache; metadata.json is written last
    for (let i = 0; i < 300 && !existsSync(path.join(dir, 'metadata.json')); i++) await new Promise((res) => setTimeout(res, 100));
    expect(existsSync(path.join(dir, 'metadata.json')), `${cachePath}/metadata.json written`).toBe(true);
    return { dzi, xml, dir, cachePath };
  }

  async attach() {
    if (this.lines.length) await this.testInfo.attach('http.log', { body: this.lines.join('\n'), contentType: 'text/plain' });
  }
}

/** The cache entry named by a DZI's tile URL: `<hash>` or `<hash>/cfg/<hash>`. */
export function cachePathOf(dzi: Dzi): string {
  const m = dzi.url?.match(/\/image_tiler\/([0-9a-f]{40}(?:\/cfg\/[0-9a-f]{40})?)\/[a-z0-9]*_?proxy_image_files\/?$/)
    ?? dzi.url?.match(/\/([0-9a-f]{40}(?:\/cfg\/[0-9a-f]{40})?)\/[^/]*image_files\/?$/);
  if (!m) throw new Error(`cannot find the cache hash in the DZI Url ${dzi.url}`);
  return m[1];
}
