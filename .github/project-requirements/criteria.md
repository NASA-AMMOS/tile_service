# Project requirements for tile_service changes

Reviewers check every pull request against these three requirements. The automated part is enforced by the checks `e2e-fast` and `data-guard` (see [docs/TESTING.md](../../docs/TESTING.md)).

## 1. Documentation parity (blocking)

User-visible behaviour changes update the docs in the same PR:
- a new or changed request parameter, servlet init-param or TileCLI option updates `README.md`;
- a new capability updates its feature description in `tests/e2e/features.yml`;
- a change to data flows or endpoints updates `docs/ARCHITECTURE.md` and the site overview (`tests/e2e/site/overview/`);
- a testing change updates `docs/TESTING.md`.

## 2. Public deployability (blocking)

The public repository and its workflows build and test anywhere:
- no internal paths (home directories, `/project/...`), hosts, runners or package repositories in code, tests, workflows or docs;
- the tests build tile_service with public dependencies only (`-P stub`); nothing in CI may resolve from a private repository;
- tests that need the real mission image library are tagged `@mission-library`; they are skipped in a stub build and must pass in a build against the real library;
- test data is publicly released or synthetic, pinned in `tests/e2e/data/manifest.json` by URL, SHA-256 and license, and fetched only from the allowlisted public archives.

`data-guard` enforces the mechanical part; reviewers check the rest.

## 3. Test coverage (blocking)

- **Bug fix:** a test that fails without the fix, annotated with `issue(...)`. If the fix resolves a `knownFailure`, delete the marker.
- **Security fix:** a test in the matching `security-*` feature that asserts the secure behaviour. It must fail on the unfixed build; do not mark it.
- **New parameter or option:** a nominal and an off-nominal test in the owning feature's spec.
- **Golden changes:** `tests/e2e/goldens/goldens.json` changes only with a stated reason in the PR description. The legacy SHA1s in `mission-products.spec.ts` and `finder-api.spec.ts` change only when the output is meant to change.

### Area changed → expected test location

| Area changed | Feature (id) | Expected tests in `tests/e2e/features/` |
|---|---|---|
| `DziTask` tiling, `RenderedImageReader`, `TileWindowImage`, `gov/nist/isg/pyramidio/**` | `dzi-tiling`, `tile-options` | `dzi-tiling.spec.ts`, `mission-products.spec.ts`, `tile-options.spec.ts` |
| plain image inputs (PNG, JPEG, TIFF) | `plain-images` | `plain-images.spec.ts` |
| stretch (`stretchType`, histogram, extrema) | `stretch` | `stretch.spec.ts` (expected limits from `support/synthetic.ts`) |
| gamma, `PNGChunks*` | `gamma` | `gamma.spec.ts` |
| overlays, `overlayPreference`, mission library upgrades | `rdr-overlay` | `rdr-overlay.spec.ts`, `mission-products.spec.ts` |
| `metadata.json`, thumbnails | `thumbnail-metadata` | `thumbnail-metadata.spec.ts` |
| task pool, progress, `maxWaitSec`, `force`, caching | `async-processing` | `async-processing.spec.ts` |
| tile URLs, `proxy`, `dzi_*_override`, `serveTileImage` | `tile-serving` | `tile-serving.spec.ts` |
| `viewer-template.html`, `serveViewer` | `viewer-page`, `security-viewer-xss` | `viewer-page.spec.ts`, `security-viewer-xss.spec.ts` |
| `Finder`, `/finder/*` | `finder-api`, `finder-label`, `security-finder-paths` | `finder-api.spec.ts`, `finder-label.spec.ts`, `security-finder-paths.spec.ts` |
| `HTTPHelper`, `S3Helper` URL handling, whitelist | `input-sources`, `security-ssrf` | `input-sources.spec.ts`, `security-ssrf.spec.ts` |
| `DziTask.filterUrl`, local paths | `url-filter`, `security-fs-paths` | `url-filter.spec.ts`, `security-fs-paths.spec.ts` |
| logging, `Utils.sanitizeForLog` | `security-log-forging` | `security-log-forging.spec.ts` |
| `TileCLI` | `cli` | `cli.spec.ts` |
| `POST` handling | `post-json` | `post-json.spec.ts` |
| `.github/workflows/**`, `tests/e2e/data/**` | — | `data-guard` passes; manifest entries pinned and licensed |
