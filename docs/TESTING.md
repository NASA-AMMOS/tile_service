# Testing tile_service

tile_service has two test layers:

| Layer | Where | Runs |
|---|---|---|
| Feature tests (Playwright Test driving TileCLI and the servlet in Tomcat) | `tests/e2e` | fast tier on every PR; full tier on every push to `main`, nightly and on release (published to GitHub Pages) |
| Legacy shell tests | `test/` | by hand; every case is ported into the feature tests |

There is no JUnit suite. The feature tests run the real product: the shaded TileCLI jar and the `war-localhost-fscache` war deployed in Tomcat 9. They use only public Mars 2020 products and synthetic images, and build tile_service with public dependencies only.

## Running locally

Prerequisites: JDK 21 or later (the build targets Java 21), Maven 3.9+, Node 20+, `unzip`, and `mission-image-stub` in your local Maven repository (see [Building without internal dependencies](#building-without-internal-dependencies)).

```bash
bash tests/e2e/scripts/build-product.sh            # tests/e2e/.cache/build/image_tiler.{jar,war}
cd tests/e2e
npm ci
npx playwright install chromium                    # for the @browser tests
npm run data:fast                                  # or data:full (adds 560 MB of products)
npx playwright test --project fast                 # or --project full
open report/index.html                             # feature overview; full Playwright report in report/playwright/
```

- **Single feature:** `npx playwright test --grep @stretch` (each feature's id is its tag).
- **Debugging the servlet:** `TILE_SERVICE_KEEP_SERVER=1` leaves Tomcat running after the run (`http://127.0.0.1:18080/image_tiler`); its log is `.cache/run/<label>/catalina.out`. The next run stops it.
- **Site:** `npm run site` builds the Pages site into `tests/e2e/public/` from the last report.

### Testing another build

The suite tests whatever artifacts the environment names, so the same specs can compare branches:

| Variable | Default | Meaning |
|---|---|---|
| `TILE_SERVICE_JAR` | `.cache/build/image_tiler.jar`, then `target/` | TileCLI jar under test |
| `TILE_SERVICE_WAR` | `.cache/build/image_tiler.war`, then `target/` | localhost-fscache war under test |
| `TILE_SERVICE_LABEL` | `local` | name of the build, shown in the report; each label gets its own Tomcat, cache and log under `.cache/run/<label>` |
| `TILE_SERVICE_REVISION` | the `REVISION` file next to the jar | revision shown in the report |
| `TILE_SERVICE_PORT` | `18080` | servlet port; the next port is the relaxed-query connector |
| `TILE_SERVICE_CLI_XMX`, `TILE_SERVICE_SERVER_XMX` | `3g`, `6g` | heap of each TileCLI run and of Tomcat |
| `TILE_SERVICE_RUN_DEFERRED` | unset | also run tests marked `deferred(...)` |
| `TILE_SERVICE_JACOCO_AGENT` | unset | JaCoCo agent jar; TileCLI runs and Tomcat append to `target/jacoco-e2e.exec` |
| `UPDATE_GOLDENS` | unset | `1` rewrites `goldens/goldens.json` |
| `SOURCE_SHA` | `git rev-parse HEAD` | commit the report's source links point at |

To build another revision without touching your checkout, use a worktree and point the suite at its artifacts:

```bash
git worktree add --detach ../tile_service-fix fix/path-traversal-log-xss
(cd ../tile_service-fix && mvn -B -P jar,stub clean package && cp target/image_tiler.jar /tmp/fix.jar \
  && mvn -B -P war-localhost-fscache,stub clean package && cp target/image_tiler.war /tmp/fix.war)
cd tests/e2e
TILE_SERVICE_LABEL=fix TILE_SERVICE_JAR=/tmp/fix.jar TILE_SERVICE_WAR=/tmp/fix.war npx playwright test --project fast
```

The revision must contain the `stub` profile (or have it applied) to build without internal dependencies. If the jar and war were built against the real library, also set `TILE_SERVICE_LIBRARY=mars`.

## Building without internal dependencies

tile_service depends on `gov.nasa.jpl.ammos.ids:mars`, the mission image library, which is only in JPL's internal Maven repository. The `stub` Maven profile swaps in `gov.nasa.jpl.ammos.ids:mission-image-stub`, an API-compatible placeholder kept in the [astria](https://github.com/NASA-AMMOS/Astria) repository (`stub/`):

```bash
mvn -P jar,stub clean package                      # TileCLI jar
mvn -P war-localhost-fscache,stub clean package    # servlet war
```

Without `-P stub` the build is unchanged and still uses `mars`. Always build with `clean` when switching: the shade plugin otherwise keeps the previous library's classes in the jar.

The stub is not published to a Maven repository. Install it from source (`git clone https://github.com/NASA-AMMOS/Astria astria && mvn -f astria/stub/pom.xml install`); no `settings.xml` or private repository is needed. `scripts/build-product.sh` checks that it is installed and explains what to do if it is not.

**What a stub build cannot do.** The stub has no mission knowledge. Every product is tiled as a plain image of product type `EDR`: tiles are the same as with the real library, but product types and instruments are not read from file names, RDR overlays are not rendered, and finder queries return nothing. Tests of those features are tagged `@mission-library` and are skipped in a stub build. Trees that differ only because of the library are recorded in `goldens/goldens.stub.json`; everything else shares `goldens/goldens.json`.

**Testing with the real library.** `TILE_SERVICE_LIBRARY=mars bash tests/e2e/scripts/build-product.sh` builds against `mars` (this needs access to it) and records the choice in `.cache/build/LIBRARY`; the suite then runs every test, including `@mission-library`, against `goldens.json` and the legacy SHA1s.

**CI.** The workflows check out the astria repository, install the stub from source and build with `-P stub`. Nothing is resolved from a private repository.

## How the feature tests work

- **Feature registry.** [`tests/e2e/features.yml`](../tests/e2e/features.yml) lists every capability (`dzi-tiling`, `stretch`, `finder-api`, `security-ssrf`, ...) with a name and description, its code paths and parameters, and its status (`active`, `planned` with a milestone, or `excluded` with a reason). `features/registry.spec.ts` fails if a spec has no feature, an active feature has no spec, a manifest asset is unpinned or unused, or a test has no description or scope tag.
- **Specs.** One or more specs per feature in `tests/e2e/features/`. Each `test.describe` carries the feature tag and a tier tag (`@fast` or `@full`). Specs import `test` and `expect` from `support/test.ts`, which adds a `source` annotation linking each test to its line on GitHub and a `tiler` fixture: an HTTP client for the servlet whose requests and responses are attached to the report as `http.log`.
- **Describing tests.** Every test passes `about(description, ...scopes)`. The description says what the test does and what makes it pass (plain text, `` `backticks` `` for code); the scope tags, defined in `SCOPES` in `support/features.ts`, say what kind of testing it is: `@nominal`, `@off-nominal`, `@malformed-input`, `@pixels`, `@golden`, `@mission-data`, `@security`, `@regression`, `@legacy`, `@browser`, `@network`, `@suite`.
- **The servlet.** `global-setup.ts` unpacks the pinned Tomcat 9 and the war under `.cache/run/<label>/`, writes a `server.xml` with two connectors (the normal one, and one with `relaxedQueryChars` for the reflected-content tests), and starts Tomcat with `srv/` as its working directory, because the localhost web.xml uses relative paths (`fs_input_dir=test`, `fs_cache_loc=test/cache`). `srv/test/` holds the products by link, `synthetic/`, the finder tree from `test/finder-data.zip`, and `alt/` (the products again, for tests other than the legacy ones). `srv/outside/` holds files no request may reach. Tomcat is stopped with SIGTERM after the run.
- **TileCLI.** `support/run.ts` runs the jar in `.cache/run/<label>/cli` with the legacy options (`-dcd tmp -it 0 -zt 0 -d -m M20 -fc -cl test/cache`). The tiler can exit 0 after an error, so `cliTile()` also requires no exception in the output and a complete pyramid.
- **Oracles.** `support/tree.ts` parses DZI descriptors, checks that a pyramid has every level (`0..ceil(log2(max(w, h)))`) and every tile with the exact size its geometry implies, decodes PNG and JPEG tiles, and reads PNG chunks (gAMA). Pixel tests compare full-resolution tiles to values computed from the input.
- **Synthetic data.** `support/synthetic.ts` writes VICAR images (BYTE, HALF, FULL, REAL; 1 and 3 bands) with pixel = base + line·linc + sample·sinc + band·binc, PNG and JPEG gradients, labels with property groups and history tasks, and broken files. Expected stretch limits (for example the 0.5% percentiles of the ramp) and tile pixels are computed from the same formulas. `freshCopy()` gives a test a private, never-tiled copy.
- **Public data.** [`tests/e2e/data/manifest.json`](../tests/e2e/data/manifest.json) pins every downloaded file by URL, SHA-256 and license: eight Mars 2020 products from the PDS Imaging Node (its public `pdsimg-m2020-public` bucket behind CloudFront) and Tomcat from the Apache release archive. `scripts/fetch-test-data.mjs` refuses unpinned files, non-HTTPS URLs, and hosts or paths outside its allowlist. Missing or altered data fails the run; it is never skipped.
- **Goldens.** `tests/e2e/goldens/goldens.json` records, per tiling, the DZI geometry, the file and tile counts and a tree hash: SHA-256 over every file's relative path and content hash, with `metadata.json` minus its volatile fields (`tiler_version`, timestamps, `rdr_url`, `cache_hash`). Regenerate with `npm run goldens:update` and say why in the PR.
- **Legacy hashes.** The SHA1s of `test/auto-test-cli.sh` and `test/auto-test-servlet.sh` are checked too. `legacyTreeSha1()` ports `test/sha1.sh` exactly, including `filter-metadata.py`'s JSON formatting and the file order `sort -z` gave under the en_US.UTF-8 locale they were recorded with, so they reproduce on any OS and locale.
- **Known failures.** `knownFailure(id, why)` marks a test as expected to fail, for a bug with a record: a ticket, or the code location until one exists. It shows in the report and the job summary, and turns red once the bug is fixed, so the marker is removed with the fix. Never use it for a regression, or for a fix that has not merged: those tests fail.
- **Deferred.** `deferred(id, why)` skips a reviewed bug we decided not to fix yet; `TILE_SERVICE_RUN_DEFERRED=1` runs it.
- **Needs review.** `needsReview(id, why)` flags behaviour that looks wrong but has no record yet. The test fails as normal, marked "needs review", until someone decides bug or intended. For behaviour that already fails on `main` (not caused by the change under test), `needsReview(id, why, { expectFail: true })` also marks the test expected to fail, so it is listed under known issues with a "needs review" tag and turns red once the behaviour changes. Ticket it, then switch to `knownFailure` with the ticket id.
- **Reports.** `support/overview-reporter.ts` writes `report/index.html` (build under test, results per feature with drop-down test lists, failures, known issues, not-yet-automated features), `report/procedures.html` (every test's description, scope, result and thumbnails, filterable by scope), `report/summary.json` (for the site) and `report/overview.md` (job summary and PR comment). The Playwright HTML report is in `report/playwright/`.

### Security tests

The `security-*` features assert the secure behaviour. A build without a fix fails them; that failure is the evidence, not something to mark.

| Feature | What it checks |
|---|---|
| `security-ssrf` | 21 internal-address URLs (loopback, private, link-local, metadata endpoints, IPv6 and encoded forms), non-whitelisted schemes and S3 look-alikes give 400, with no outbound connection |
| `security-fs-paths` | absolute paths, `file:///`, `..` and NUL in `image` give 400, whether or not the file exists |
| `security-finder-paths` | `..`, absolute and leading-slash paths in `/finder/*` give 400 and do not disclose `srv/outside/` files |
| `security-log-forging` | a CR/LF in a parameter does not split the `[request]` prefix of log lines (read from the service log) |
| `security-viewer-xss` | the viewer reflects the query percent-encoded; raw `'`, `"` and `</script>` payloads, sent byte for byte, do not run script in Chromium |

## Data sensitivity

The public repository must never depend on internal locations. `scripts/check-data-sensitivity.sh` (CI job `data-guard`) fails if the test code, legacy tests, testing container, workflows or this guide contain internal paths (`/Users/`, `/home/`, `/project/`, `/private/tmp/`), internal hosts (`*.jpl.nasa.gov` other than the public PDS hosts), internal runners or the internal Maven repository, or if a manifest entry is unpinned, unlicensed or off the allowlist.

## Adding tests

### What to write

| Change | Tests |
|---|---|
| New request parameter or CLI option | a nominal and an off-nominal test in the owning feature's spec |
| Bug fix | a test that fails without the fix, with `issue(...)`; remove any `knownFailure` it fixes |
| New capability | a feature in `features.yml` and a spec that calls `feature('<id>')` |
| Security fix | a `security-*` test asserting the secure behaviour |
| Output change (tiles, stretch, metadata) | goldens updated with the reason in the PR |

### Anatomy of a test

```ts
import { expect, test } from '../support/test';
import { about, feature } from '../support/features';
import { VICAR } from '../support/synthetic';

test.describe('Contrast stretch', feature('stretch'), () => {
  test('stretchType=extrema maps the given DN range linearly onto 0..255', about(
    `Tiles the HALF ramp with \`stretchType=extrema&stretchLow=100&stretchHigh=1000&gammaMode=none\`. Passes when ...`,
    '@nominal', '@pixels',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/half_ramp.vic&stretchType=extrema&stretchLow=100&stretchHigh=1000&gammaMode=none');
    // t.dzi (parsed descriptor), t.dir (cache entry on disk), t.cachePath
  });
});
```

Tests that change a cache entry (`force=true`, variants of the legacy products) use `freshCopy()` or `alt/` paths so they do not race other tests.

### New public data

Add an entry to `data/manifest.json` with `sha256: null`, run `npm run data:pin`, review the diff, and use the asset by its id (`asset('<id>')`) in a spec. Only public archives on the allowlist in `scripts/fetch-test-data.mjs` are allowed; add a host only for a public archive.

### Before opening the pull request

```bash
npm run guard
npx playwright test --project fast
```

## CI and reports

| Workflow | Trigger | Jobs |
|---|---|---|
| `ci.yml` | pull requests to `main` and `develop`, dispatch | `e2e-fast` (fast tier, report artifact, job summary, PR comment), `data-guard` |
| `publish.yml` | push to `main`, nightly 07:00 UTC, release, dispatch | `e2e-full` (full tier), then builds the site and deploys it to GitHub Pages |
| `security-scan.yml` | see the workflow | CodeQL and SonarQube Cloud |

- **Job summary and PR comment:** `report/overview.md`.
- **Artifact:** `feature-report-<tier>` holds `report/`, `results/` (JUnit XML and JSON) and the service log; open `report/index.html`.
- **Site:** `https://nasa-ammos.github.io/tile_service/`: the home page, the architecture overview (`site/overview/`), and the latest full-tier report under `tests/`. Pages must use "GitHub Actions" as its source.

## Branch protection

Make `e2e-fast` and `data-guard` required checks for `main` and `develop`:

```bash
for b in main develop; do
  gh api -X PUT repos/NASA-AMMOS/tile_service/branches/$b/protection --input - <<'JSON'
{ "required_status_checks": { "strict": true, "contexts": ["e2e-fast", "data-guard"] },
  "enforce_admins": false, "required_pull_request_reviews": { "required_approving_review_count": 1 }, "restrictions": null }
JSON
done
```
