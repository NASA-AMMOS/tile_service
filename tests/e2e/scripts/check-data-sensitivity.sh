#!/usr/bin/env bash
# Fails if test code, workflows or docs reference internal locations, or if
# test data comes from anywhere but the public allowlist in fetch-test-data.mjs.
# Run from anywhere: bash tests/e2e/scripts/check-data-sensitivity.sh
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
report() { echo "data-guard: $*" >&2; fail=1; }

# Public areas that drive the public test workflow (tracked files, plus new
# files not yet committed when run locally).
paths=(tests/e2e test testing-container .github/workflows .github/project-requirements docs/TESTING.md)
# the policy docs name the forbidden patterns, so they are exempt; security-scan.yml
# only links the scanning guide it follows and runs no tests or data
excludes=(':!tests/e2e/package-lock.json' ':!tests/e2e/scripts/check-data-sensitivity.sh'
  ':!docs/TESTING.md' ':!.github/project-requirements/criteria.md' ':!.github/workflows/security-scan.yml')

# Internal file systems, runners, hosts and package repositories
patterns=(
  '/project/'
  '/home/'
  '/Users/'
  '/private/tmp/'
  'jpl-linux-x64'
  '[Aa]rtifactory'
  '[A-Za-z0-9.-]*\.jpl\.nasa\.gov'
)
for p in "${patterns[@]}"; do
  hits=$(git grep --untracked -nIE "$p" -- "${paths[@]}" "${excludes[@]}" 2>/dev/null \
    | grep -vE '(planetarydata|pds-imaging)\.jpl\.nasa\.gov' || true)
  if [ -n "$hits" ]; then
    report "internal reference matching '$p':"
    echo "$hits" >&2
  fi
done

# Every data URL must pass the fetch script's allowlist (and be pinned)
node --input-type=module -e "
  import { readFileSync } from 'node:fs';
  import { checkUrl } from './tests/e2e/scripts/fetch-test-data.mjs';
  const m = JSON.parse(readFileSync('tests/e2e/data/manifest.json', 'utf8'));
  let bad = 0;
  for (const a of m.assets) {
    const why = checkUrl(a.url) || (/^[0-9a-f]{64}$/.test(a.sha256 ?? '') ? null : 'sha256 not pinned') || (a.license ? null : 'no license');
    if (why) { console.error('data-guard: ' + a.id + ': ' + why); bad = 1; }
  }
  process.exit(bad);
" || fail=1

if [ "$fail" -ne 0 ]; then
  echo "data-guard: FAILED (public tests may use only publicly released data and generic paths)" >&2
  exit 1
fi
echo "data-guard: OK"
