// Runs TileCLI (the shaded jar) and checks its results. Every run is attached
// to the test as <name>.log: command, exit code, stdout and stderr.
import { execFile } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import type { TestInfo } from '@playwright/test';
import { expect } from '@playwright/test';
import { CLI_XMX, PRODUCT_JAVA, productJar } from './env';
import { CLI_DIR } from './server';
import { treeDigest, type Dzi, expectCompletePyramid } from './tree';
import { asset } from './data';

export interface RunResult { cmd: string[]; exitCode: number; stdout: string; stderr: string; durationMs: number }

export function run(cmd: string[], opts: { cwd?: string; timeoutMs?: number } = {}): Promise<RunResult> {
  const start = Date.now();
  return new Promise((resolve) => {
    execFile(cmd[0], cmd.slice(1), { cwd: opts.cwd, timeout: opts.timeoutMs ?? 300_000, maxBuffer: 256 * 1024 * 1024 },
      (err, stdout, stderr) => {
        const code = err ? (typeof (err as { code?: unknown }).code === 'number' ? (err as { code: number }).code : 1) : 0;
        resolve({ cmd, exitCode: code, stdout: String(stdout), stderr: String(stderr), durationMs: Date.now() - start });
      });
  });
}

const LOG_SAFE = (s: string) => s.replace(/[^A-Za-z0-9._-]+/g, '_').slice(0, 80);

async function attachRun(testInfo: TestInfo, name: string, r: RunResult) {
  await testInfo.attach(`${LOG_SAFE(name)}.log`, {
    body: `$ ${r.cmd.join(' ')}\n(cwd ${CLI_DIR})\nexit code: ${r.exitCode} (${r.durationMs} ms)\n\n--- stdout ---\n${r.stdout}\n--- stderr ---\n${r.stderr}`,
    contentType: 'text/plain',
  });
}

/**
 * Runs `java -jar image_tiler.jar ARGS` in .cache/run/<label>/cli, whose test/
 * holds the public products, synthetic/ and the finder tree.
 */
export async function tileCli(testInfo: TestInfo, args: string[], name = 'TileCLI'): Promise<RunResult> {
  const r = await run([...PRODUCT_JAVA, `-Xmx${CLI_XMX}`, '-XX:-TieredCompilation', '-Djava.awt.headless=true', '-jar', productJar(), ...args],
    { cwd: CLI_DIR });
  await attachRun(testInfo, name, r);
  return r;
}

/** The legacy test/run-cli.sh options, with the cache under test/cache. */
export const LEGACY_ARGS = (input: string) => ['-dcd', 'tmp', '-it', '0', '-zt', '0', '-d', '-i', input, '-m', 'M20', '-fc', '-cl', 'test/cache'];

/** Exit code 0 and no exception or ERROR line in the output. */
export function expectCliOk(r: RunResult) {
  const out = `${r.stdout}\n${r.stderr}`;
  expect(r.exitCode, `exit code of ${r.cmd.slice(-12).join(' ')}\n${out.slice(-2000)}`).toBe(0);
  expect(out.match(/^.*(Exception|\bERROR\b).*$/m)?.[0] ?? '', 'no exception or ERROR in the output').toBe('');
}

/** The cache directory TileCLI uses for an input, relative to the CLI working directory (test/cache/<hash>). */
export async function cliCacheDir(testInfo: TestInfo, input: string, extra: string[] = []): Promise<string> {
  const r = await tileCli(testInfo, ['-i', input, '-m', 'M20', '-fc', '-pc', '-q', ...extra], 'print-cache-dir');
  expect(r.exitCode).toBe(0);
  const hash = r.stdout.trim().split('\n').pop()!.trim();
  expect(hash, 'TileCLI -pc prints a 40-hex cache hash').toMatch(/^[0-9a-f]{40}$/);
  return `test/cache/${hash}`;
}

/**
 * Tiles an input (relative to the CLI working directory, e.g. test/<file>) with
 * the legacy options plus `extra`, and returns the finished tree's DZI, digest
 * and directory (taken from the "saved N files at <dir>" log line).
 */
export async function cliTile(testInfo: TestInfo, input: string, extra: string[] = []) {
  const r = await tileCli(testInfo, [...LEGACY_ARGS(input), ...extra], `tile ${path.basename(input)}`);
  expectCliOk(r);
  const rel = `${r.stdout}\n${r.stderr}`.match(/saved \d+ files at (\S+)/)?.[1];
  expect(rel, 'TileCLI logs where it saved the tiles').toBeTruthy();
  const dir = path.resolve(CLI_DIR, rel!);
  const dzi: Dzi = expectCompletePyramid(dir);
  return { r, dzi, dir, rel: rel!, digest: treeDigest(dir) };
}

/** Attaches a thumbnail as a "<name> preview" image for the report galleries; public products are marked [mission data]. */
export async function attachPreview(testInfo: TestInfo, dir: string, name: string, mission: boolean) {
  const f = path.join(dir, 'thumbnail.png');
  if (existsSync(f)) {
    await testInfo.attach(`${name} preview${mission ? ' [mission data]' : ''}`, { body: readFileSync(f), contentType: 'image/png' });
  }
}

/** The CLI input path of a manifest product (test/<file>, a link to .cache/data). */
export const productInput = (id: string) => `test/${path.basename(asset(id))}`;
