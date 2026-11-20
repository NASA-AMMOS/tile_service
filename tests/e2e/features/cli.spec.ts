// TileCLI options other than tiling itself.
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { cliCacheDir, tileCli } from '../support/run';
import { readFileSync } from 'node:fs';
import { REPO_ROOT } from '../support/env';

const semver = () => readFileSync(path.join(REPO_ROOT, 'pom.xml'), 'utf8').match(/<semver>([^<]+)<\/semver>/)![1];

test.describe('TileCLI command line', feature('cli'), () => {
  test('-v prints the version', about(
    `Runs \`TileCLI -v\`. Passes when it exits 0 and prints "TileCLI <version>" with the pom's version.`,
    '@nominal',
  ), async ({}, testInfo) => {
    const r = await tileCli(testInfo, ['-v'], 'version');
    expect(r.exitCode).toBe(0);
    expect(`${r.stdout}${r.stderr}`).toMatch(new RegExp(`TileCLI ${semver().replace(/\./g, '\\.')}`));
  });

  test('-h prints the options', about(
    `Runs \`TileCLI -h\`. Passes when it exits 0 and the help lists the main options (\`-i\`, \`-fc\`, \`-cl\`, \`-st\`, \`-tf\`).`,
    '@nominal',
  ), async ({}, testInfo) => {
    const r = await tileCli(testInfo, ['-h'], 'help');
    expect(r.exitCode).toBe(0);
    const out = `${r.stdout}${r.stderr}`;
    for (const o of ['-i,', '-fc,', '-cl,', '-st,', '-tf,']) expect(out).toContain(o);
  });

  test('-pc prints the same cache entry the servlet uses', about(
    `Runs \`TileCLI -i test/synthetic/byte_rgb.vic -m M20 -fc -pc -q\` and requests the same image from the servlet (\`image=synthetic/byte_rgb.vic\` with \`fs_input_dir=test\`). Passes when the hash TileCLI prints is the cache entry the servlet's DZI names, so CLI pre-tiling serves servlet requests.`,
    '@nominal',
  ), async ({ tiler }, testInfo) => {
    const rel = await cliCacheDir(testInfo, 'test/synthetic/byte_rgb.vic');
    const t = await tiler.tile('image=synthetic/byte_rgb.vic');
    expect(rel).toBe(`test/cache/${t.cachePath}`);
  });

  test('-dlc on a local input is refused', about(
    `Runs \`TileCLI -i test/synthetic/byte_rgb.vic -fc -dlc\` (delete cache, which needs an S3 input). Passes when it exits 1 and says the input is not an S3 URL.`,
    '@off-nominal',
  ), async ({}, testInfo) => {
    const r = await tileCli(testInfo, ['-i', 'test/synthetic/byte_rgb.vic', '-fc', '-dlc'], 'delete-cache');
    expect(r.exitCode).toBe(1);
    expect(`${r.stdout}${r.stderr}`).toContain('input is not an S3 URL');
  });

  test('an unknown option is refused', about(
    `Runs \`TileCLI --no-such-option\`. Passes when it exits non-zero.`,
    '@off-nominal',
  ), async ({}, testInfo) => {
    const r = await tileCli(testInfo, ['--no-such-option'], 'bad-option');
    expect(r.exitCode).not.toBe(0);
  });
});
