#!/usr/bin/env node
// Posts the feature summary (report/overview.md) on the pull request as a
// single comment, updated in place on every push.
// Env: GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, REPORT_URL, RUN_URL.
import { existsSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const MARKER = '<!-- tile-service-feature-report -->';
const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const { GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, REPORT_URL, RUN_URL } = process.env;
if (!GITHUB_TOKEN || !GITHUB_REPOSITORY || !PR_NUMBER) {
  console.error('GITHUB_TOKEN, GITHUB_REPOSITORY and PR_NUMBER are required');
  process.exit(2);
}

const md = join(root, 'report', 'overview.md');
const summary = existsSync(md) ? readFileSync(md, 'utf8') : '### tile_service feature tests\n\nThe tests did not produce a report; see the workflow run.\n';
const links = [
  REPORT_URL ? `**[Download the full report](${REPORT_URL})** (zip, kept 5 days): open \`report/index.html\` for the feature overview and test procedures.` : '',
  RUN_URL ? `[Workflow run](${RUN_URL})` : '',
].filter(Boolean).join(' · ');
const body = `${MARKER}\n${summary.trim()}\n\n${links}\n`;

const api = `https://api.github.com/repos/${GITHUB_REPOSITORY}`;
const headers = { Authorization: `Bearer ${GITHUB_TOKEN}`, Accept: 'application/vnd.github+json', 'X-GitHub-Api-Version': '2022-11-28' };

async function call(method, url, payload) {
  const res = await fetch(url, { method, headers, body: payload ? JSON.stringify(payload) : undefined });
  if (!res.ok) throw new Error(`${method} ${url}: HTTP ${res.status} ${await res.text()}`);
  return res.json();
}

let existing;
for (let page = 1; !existing; page++) {
  const comments = await call('GET', `${api}/issues/${PR_NUMBER}/comments?per_page=100&page=${page}`);
  existing = comments.find((c) => c.body?.startsWith(MARKER) && c.user?.type === 'Bot');
  if (comments.length < 100) break;
}
if (existing) {
  await call('PATCH', `${api}/issues/comments/${existing.id}`, { body });
  console.log(`updated comment ${existing.html_url}`);
} else {
  const c = await call('POST', `${api}/issues/${PR_NUMBER}/comments`, { body });
  console.log(`posted comment ${c.html_url}`);
}
