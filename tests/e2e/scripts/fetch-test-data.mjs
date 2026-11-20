#!/usr/bin/env node
// Download the public test data listed in data/manifest.json into .cache/data,
// verifying every file against its pinned SHA-256.
//
//   node scripts/fetch-test-data.mjs --tier fast|full [--pin] [--dest DIR]
//
// --tier full includes the fast assets. --pin downloads assets whose sha256 is
// null and records their digest and size in the manifest (maintainers only;
// review the diff). Without --pin an unpinned asset is an error, as is any URL
// whose host is not on the allowlist below: test data must be publicly
// released, never internal. Missing or corrupt data fails, it never skips.
import { createHash } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const manifestPath = join(root, 'data', 'manifest.json');

// Public archives only. Each host is listed with the path prefixes it may serve.
const ALLOWED = new Map([
  // PDS Imaging Node: the public Mars 2020 bucket pdsimg-m2020-public behind CloudFront
  ['d1ejlg980osaur.cloudfront.net', /^\/m20\/r\d+\/mars2020_[a-z0-9_]+\/data\//],
  ['planetarydata.jpl.nasa.gov', /^\/img\/data\//], // PDS Imaging Node archive
  ['pds-imaging.jpl.nasa.gov', /^\/data\//],
  // Apache release archive (Tomcat, the servlet container under test)
  ['archive.apache.org', /^\/dist\/tomcat\/tomcat-\d+\/v[\d.]+\/bin\/apache-tomcat-[\d.]+\.(zip|tar\.gz)$/],
]);

const args = process.argv.slice(2);
const opt = (name, dflt) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : dflt;
};
const tier = opt('--tier', 'fast');
const pin = args.includes('--pin');
const dest = opt('--dest', join(root, '.cache', 'data'));
if (!['fast', 'full'].includes(tier)) {
  console.error(`--tier must be fast or full, got ${tier}`);
  process.exit(2);
}

export function checkUrl(url) {
  let u;
  try { u = new URL(url); } catch { return `not a URL: ${url}`; }
  if (u.protocol !== 'https:') return `not https: ${url}`;
  if (u.username || u.password || u.port) return `credentials or port in URL: ${url}`;
  const prefix = ALLOWED.get(u.hostname);
  if (!prefix) return `host not on the public-data allowlist: ${u.hostname}`;
  return prefix.test(u.pathname) ? null : `path not allowed for ${u.hostname}: ${u.pathname}`;
}

const sha256 = (buf) => createHash('sha256').update(buf).digest('hex');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function download(url) {
  for (let attempt = 1; ; attempt++) {
    try {
      const res = await fetch(url, { headers: { 'User-Agent': 'tile-service-feature-tests (github.com/NASA-AMMOS/tile_service)' } });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      return Buffer.from(await res.arrayBuffer());
    } catch (err) {
      if (attempt >= 4) throw new Error(`${url}: ${err.message}`);
      await sleep(2000 * attempt); // polite backoff
    }
  }
}

async function main() {
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  const assets = manifest.assets.filter((a) => tier === 'full' || a.tier === 'fast');
  const errors = [];
  const files = new Set();
  for (const a of manifest.assets) {
    if (files.has(a.file)) errors.push(`duplicate file name ${a.file}`);
    files.add(a.file);
    const bad = checkUrl(a.url);
    if (bad) errors.push(`${a.id}: ${bad}`);
    if (!a.license) errors.push(`${a.id}: license missing`);
  }
  if (errors.length) {
    errors.forEach((e) => console.error(`ERROR ${e}`));
    process.exit(1);
  }

  mkdirSync(dest, { recursive: true });
  let changed = false;
  for (const a of assets) {
    const target = join(dest, a.file);
    if (!a.sha256 && !pin) {
      errors.push(`${a.id}: no sha256 pinned (run npm run data:pin and review the diff)`);
      continue;
    }
    if (a.sha256 && existsSync(target) && sha256(readFileSync(target)) === a.sha256) {
      console.log(`cached   ${a.file}`);
      continue;
    }
    const buf = await download(a.url);
    const digest = sha256(buf);
    if (!a.sha256) {
      a.sha256 = digest;
      a.bytes = buf.length;
      changed = true;
      console.log(`pinned   ${a.file} ${digest} (${buf.length} bytes)`);
    } else if (digest !== a.sha256) {
      errors.push(`${a.id}: sha256 mismatch, expected ${a.sha256} got ${digest}`);
      continue;
    } else {
      console.log(`fetched  ${a.file} (${buf.length} bytes)`);
    }
    writeFileSync(`${target}.part`, buf);
    renameSync(`${target}.part`, target);
    await sleep(250);
  }

  if (changed) {
    writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n');
    writeFileSync(join(root, 'data', 'manifest.sha256'),
      manifest.assets.filter((a) => a.sha256).map((a) => `${a.sha256}  ${a.file}`).join('\n') + '\n');
    console.log('manifest updated; review and commit data/manifest.json and data/manifest.sha256');
  }
  if (errors.length) {
    errors.forEach((e) => console.error(`ERROR ${e}`));
    process.exit(1);
  }
  console.log(`${assets.length} ${tier}-tier assets verified in ${dest}`);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((err) => {
    console.error(`ERROR ${err.message}`);
    process.exit(1);
  });
}
