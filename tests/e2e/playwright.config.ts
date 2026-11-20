import { defineConfig } from '@playwright/test';
import { LIBRARY } from './support/env';

// a build against mission-image-stub has no mission knowledge: skip the tests that need it
const grepInvert = LIBRARY === 'stub' ? /@mission-library/ : undefined;

// Specs drive TileCLI and the image_tiler servlet (Tomcat, started by
// global-setup.ts) and decode their output; only @browser tests open Chromium.
export default defineConfig({
  testDir: './features',
  globalSetup: './global-setup.ts',
  outputDir: 'test-results',
  timeout: 5 * 60_000,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  // each worker may run a TileCLI JVM next to Tomcat; goldens are rewritten one test at a time
  workers: process.env.UPDATE_GOLDENS === '1' ? 1 : process.env.CI ? 2 : 4,
  use: { headless: true },
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'report/playwright', title: 'tile_service feature tests' }],
    // landing page report/index.html (feature overview), procedures.html and overview.md
    ['./support/overview-reporter.ts', { outputFolder: 'report' }],
    ['junit', { outputFile: 'results/junit.xml' }],
    ['json', { outputFile: 'results/results.json' }],
  ],
  projects: [
    // PR gate: synthetic inputs, the finder tree and three small public products
    { name: 'fast', grep: /@fast/, grepInvert },
    // main and nightly: adds the remaining public products and network input
    { name: 'full', grep: /@fast|@full/, grepInvert },
  ],
});
