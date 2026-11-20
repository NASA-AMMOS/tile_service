// Runs once before the specs: checks the build under test and the pinned data,
// writes the synthetic inputs, lays out .cache/run/<label> and starts Tomcat
// with the war. The returned function stops Tomcat after the run.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, rmSync } from 'node:fs';
import path from 'node:path';
import { manifestAssets } from './support/data';
import { DATA_DIR, JACOCO_AGENT, JACOCO_E2E_EXEC, LABEL, SYNTH_DIR, productJar, productRevision, productWar } from './support/env';
import { startServer, stopServer } from './support/server';
import { generateSynthetic } from './support/synthetic';

export default async function globalSetup() {
  const jar = productJar();
  const war = productWar();
  // Verify the data up front: inside a knownFailure test a missing or corrupt
  // asset would otherwise be absorbed as the "expected" failure.
  const full = process.argv.some((a) => /full/.test(a)) || process.env.TILE_SERVICE_TIER === 'full';
  const problems: string[] = [];
  for (const a of manifestAssets()) {
    const file = path.join(DATA_DIR, a.file);
    if (!existsSync(file)) {
      if (a.tier === 'fast' || full) problems.push(`${a.file} missing`);
    } else if (createHash('sha256').update(readFileSync(file)).digest('hex') !== a.sha256) {
      problems.push(`${a.file} does not match its pinned sha256`);
    }
  }
  if (problems.length) throw new Error(`test data not ready (run npm run data:${full ? 'full' : 'fast'}):\n  ${problems.join('\n  ')}`);

  if (JACOCO_AGENT) {
    if (!existsSync(JACOCO_AGENT)) throw new Error(`TILE_SERVICE_JACOCO_AGENT=${JACOCO_AGENT} does not exist`);
    rmSync(JACOCO_E2E_EXEC, { force: true });
  }

  rmSync(SYNTH_DIR, { recursive: true, force: true });
  generateSynthetic();
  const s = await startServer();
  const rev = productRevision();
  console.log(`tile_service feature tests: build "${LABEL}"${rev ? ` (${rev})` : ''}\n  jar ${jar}\n  war ${war}\n  servlet ${s.baseUrl}/image_tiler, log ${path.relative(process.cwd(), s.log)}`);
  // TILE_SERVICE_KEEP_SERVER=1 leaves Tomcat running after the run, for debugging with curl
  return async () => { if (!process.env.TILE_SERVICE_KEEP_SERVER) await stopServer(s.pid); };
}
