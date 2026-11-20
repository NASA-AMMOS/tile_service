// Paths, executables and the build under test. Everything is relative to the
// repository; nothing here may point at site-specific locations.
import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const E2E_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const REPO_ROOT = path.resolve(E2E_ROOT, '..', '..');
export const CACHE = path.join(E2E_ROOT, '.cache');
export const DATA_DIR = path.join(CACHE, 'data');
export const SYNTH_DIR = path.join(CACHE, 'synthetic');
export const BUILD_DIR = path.join(CACHE, 'build');
export const FINDER_ZIP = path.join(REPO_ROOT, 'test', 'finder-data.zip');

/**
 * The mission image library the build under test was linked with: `stub`
 * (gov.nasa.jpl.ammos.ids:mission-image-stub, the public default: every product
 * is a plain image) or `mars` (the real library). scripts/build-product.sh
 * records it in .cache/build/LIBRARY; TILE_SERVICE_LIBRARY overrides it for a
 * build supplied through TILE_SERVICE_JAR / TILE_SERVICE_WAR. Tests tagged
 * @mission-library are skipped with the stub.
 */
export const LIBRARY: 'stub' | 'mars' = (() => {
  const recorded = path.join(BUILD_DIR, 'LIBRARY');
  const v = (process.env.TILE_SERVICE_LIBRARY || (existsSync(recorded) ? readFileSync(recorded, 'utf8') : 'stub')).trim();
  if (v !== 'stub' && v !== 'mars') throw new Error(`mission library must be 'stub' or 'mars', got '${v}'`);
  return v;
})();

export const JAVA = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', 'java') : 'java';

/**
 * A name for the build under test (TILE_SERVICE_LABEL, e.g. `main` or `fix`),
 * shown in the report. Runs with different labels keep separate servers,
 * caches and logs under .cache/run/<label>.
 */
export const LABEL = (process.env.TILE_SERVICE_LABEL || 'local').replace(/[^A-Za-z0-9._-]/g, '_');
export const RUN_DIR = path.join(CACHE, 'run', LABEL);

/** Port of the servlet under test; the next port is a second connector with relaxed query characters. */
export const PORT = Number(process.env.TILE_SERVICE_PORT || 18080);

/** Max heap of each TileCLI run and of Tomcat. */
export const CLI_XMX = process.env.TILE_SERVICE_CLI_XMX || '3g';
export const SERVER_XMX = process.env.TILE_SERVICE_SERVER_XMX || '6g';

/**
 * JaCoCo: with $TILE_SERVICE_JACOCO_AGENT set to the agent jar, every TileCLI
 * run and the Tomcat JVM append coverage to target/jacoco-e2e.exec. The
 * product runs with PRODUCT_JAVA; helpers that only inspect output do not.
 */
export const JACOCO_AGENT = process.env.TILE_SERVICE_JACOCO_AGENT ? path.resolve(process.env.TILE_SERVICE_JACOCO_AGENT) : undefined;
export const JACOCO_E2E_EXEC = path.join(REPO_ROOT, 'target', 'jacoco-e2e.exec');
export const JACOCO_OPT = JACOCO_AGENT
  ? [`-javaagent:${JACOCO_AGENT}=destfile=${JACOCO_E2E_EXEC},append=true,includes=jpl.mipl.*:gov.nist.*`] : [];
export const PRODUCT_JAVA: string[] = [JAVA, ...JACOCO_OPT];

function artifact(envVar: string, file: string): string {
  const fromEnv = process.env[envVar];
  if (fromEnv) {
    if (!existsSync(fromEnv)) throw new Error(`${envVar}=${fromEnv} does not exist`);
    return path.resolve(fromEnv);
  }
  for (const p of [path.join(BUILD_DIR, file), path.join(REPO_ROOT, 'target', file)]) if (existsSync(p)) return p;
  throw new Error(`no ${file}: run npm run build:product (or set ${envVar})`);
}

/** The shaded TileCLI jar: $TILE_SERVICE_JAR, else .cache/build/ (npm run build:product), else target/. */
export const productJar = () => artifact('TILE_SERVICE_JAR', 'image_tiler.jar');

/** The localhost-fscache war: $TILE_SERVICE_WAR, else .cache/build/, else target/. */
export const productWar = () => artifact('TILE_SERVICE_WAR', 'image_tiler.war');

/**
 * The revision of the build under test: $TILE_SERVICE_REVISION, else the
 * REVISION file written next to the jar by build-product.sh, else unknown.
 */
export function productRevision(): string {
  if (process.env.TILE_SERVICE_REVISION) return process.env.TILE_SERVICE_REVISION;
  try {
    const f = path.join(path.dirname(productJar()), 'REVISION');
    if (existsSync(f)) return readFileSync(f, 'utf8').trim();
  } catch { /* no jar yet */ }
  return '';
}

export const synthetic = (name: string) => path.join(SYNTH_DIR, name);

let sourceSha: string | undefined;

/**
 * GitHub link to a line of a test file, at the commit under test
 * ($SOURCE_SHA in CI, else the local HEAD; falls back to main).
 */
export function sourceUrl(file: string, line?: number): string {
  if (sourceSha === undefined) {
    sourceSha = process.env.SOURCE_SHA || process.env.GITHUB_SHA || '';
    if (!sourceSha) {
      try {
        sourceSha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: E2E_ROOT, stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
      } catch { /* not a checkout */ }
    }
  }
  const repo = process.env.GITHUB_SERVER_URL && process.env.GITHUB_REPOSITORY
    ? `${process.env.GITHUB_SERVER_URL}/${process.env.GITHUB_REPOSITORY}` : 'https://github.com/NASA-AMMOS/tile_service';
  const rel = path.relative(REPO_ROOT, file).split(path.sep).join('/');
  return `${repo}/blob/${sourceSha || 'main'}/${rel}${line ? `#L${line}` : ''}`;
}
