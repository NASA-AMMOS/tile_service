// The Playwright test object used by every spec: adds a "source" annotation
// linking each test to its code on GitHub, and a `tiler` fixture (an HTTP
// client for the servlet under test whose exchanges are attached as http.log).
import { test as base } from '@playwright/test';
import { sourceUrl } from './env';
import { Tiler } from './server';

export const test = base.extend<{ sourceLink: void; tiler: Tiler }>({
  sourceLink: [async ({}, use, testInfo) => {
    testInfo.annotations.push({ type: 'source', description: sourceUrl(testInfo.file, testInfo.line) });
    await use();
  }, { auto: true }],
  tiler: async ({ request }, use, testInfo) => {
    const t = new Tiler(request, testInfo);
    await use(t);
    await t.attach();
  },
});

export { expect } from '@playwright/test';
