// Oracles for tiler output: DZI descriptors, tile pyramids on disk, decoded
// tiles, and the tree hashes used by the goldens and by the legacy shell tests.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import jpeg from 'jpeg-js';
import { PNG } from 'pngjs';
import { expect } from '@playwright/test';

export interface Dzi { tileSize: number; overlap: number; format: string; width: number; height: number; url?: string }

/** Parses a Deep Zoom descriptor (image.dzi or the servlet's DZI response). */
export function parseDzi(xml: string): Dzi {
  const attr = (el: string, name: string) => xml.match(new RegExp(`<${el}\\b[^>]*\\b${name}="([^"]*)"`))?.[1];
  const num = (el: string, name: string) => {
    const v = attr(el, name);
    if (v === undefined) throw new Error(`DZI has no ${el} ${name}: ${xml.slice(0, 300)}`);
    return Number(v);
  };
  return {
    tileSize: num('Image', 'TileSize'), overlap: num('Image', 'Overlap'), format: attr('Image', 'Format') ?? '',
    width: num('Size', 'Width'), height: num('Size', 'Height'), url: attr('Image', 'Url'),
  };
}

/** Deep Zoom pyramid geometry: level 0 is 1x1, the top level is full size. */
export function levels(width: number, height: number) {
  const top = Math.ceil(Math.log2(Math.max(width, height)));
  return Array.from({ length: top + 1 }, (_, level) => {
    const scale = 2 ** (top - level);
    return { level, width: Math.ceil(width / scale), height: Math.ceil(height / scale) };
  });
}

/** The tile file names (`<col>_<row>`) expected at a level. */
export function tileGrid(levelWidth: number, levelHeight: number, tileSize: number): string[] {
  const out: string[] = [];
  for (let c = 0; c < Math.ceil(levelWidth / tileSize); c++) for (let r = 0; r < Math.ceil(levelHeight / tileSize); r++) out.push(`${c}_${r}`);
  return out.sort();
}

/** Width and height of tile (col, row) at a level of the given size. */
export function tileSize(dzi: Pick<Dzi, 'tileSize' | 'overlap'>, lw: number, lh: number, col: number, row: number) {
  const x0 = Math.max(0, col * dzi.tileSize - dzi.overlap);
  const y0 = Math.max(0, row * dzi.tileSize - dzi.overlap);
  const x1 = Math.min(lw, (col + 1) * dzi.tileSize + dzi.overlap);
  const y1 = Math.min(lh, (row + 1) * dzi.tileSize + dzi.overlap);
  return { x0, y0, width: x1 - x0, height: y1 - y0 };
}

export interface Decoded { width: number; height: number; data: Buffer | Uint8Array; channels: 4; alpha: boolean; gray: boolean; chunks: string[] }

/** PNG chunk types in file order (IHDR, gAMA, sRGB, ...). */
export function pngChunks(buf: Buffer): string[] {
  const out: string[] = [];
  for (let o = 8; o + 8 <= buf.length;) {
    const len = buf.readUInt32BE(o);
    out.push(buf.toString('latin1', o + 4, o + 8));
    o += 12 + len;
  }
  return out;
}

/** Decodes a PNG or JPEG tile to RGBA. */
export function decode(buf: Buffer): Decoded {
  if (buf.subarray(1, 4).toString('latin1') === 'PNG') {
    const png = PNG.sync.read(buf);
    const colorType = buf.readUInt8(25);
    return { width: png.width, height: png.height, data: png.data, channels: 4, alpha: (colorType & 4) !== 0,
      gray: (colorType & 2) === 0, chunks: pngChunks(buf) };
  }
  if (buf[0] === 0xff && buf[1] === 0xd8) {
    const j = jpeg.decode(buf, { useTArray: true, formatAsRGBA: true });
    return { width: j.width, height: j.height, data: j.data, channels: 4, alpha: false, gray: false, chunks: [] };
  }
  throw new Error(`not a PNG or JPEG (starts ${buf.subarray(0, 8).toString('hex')})`);
}

export const pixel = (d: Decoded, x: number, y: number) => {
  const i = (y * d.width + x) * 4;
  return [d.data[i], d.data[i + 1], d.data[i + 2], d.data[i + 3]];
};

function walk(dir: string, out: string[] = []): string[] {
  for (const f of readdirSync(dir)) {
    const p = path.join(dir, f);
    if (statSync(p).isDirectory()) walk(p, out); else out.push(p);
  }
  return out;
}

/** Fields of metadata.json that change from run to run, or with the cache location. */
export const VOLATILE_METADATA = ['tiler_version', 'processing_timestamp', 'rdr_timestamp'];

/**
 * A tile tree on disk reduced to one hash: sha256 over `<relative path> <sha256>`
 * lines sorted bytewise, with metadata.json minus its volatile fields and minus
 * `rdr_url` and `cache_hash` (which depend on where the input and cache are).
 * `cfg/` (custom-parameter variants cached under the nominal entry) is left out.
 */
export function treeDigest(dir: string) {
  const files = walk(dir).map((f) => path.relative(dir, f).split(path.sep).join('/'))
    .filter((f) => !f.startsWith('cfg/') && !/(^|\/)(progress\.txt|error\.txt)$/.test(f)).sort();
  const lines = files.map((f) => {
    let body: Buffer | string = readFileSync(path.join(dir, f));
    if (f === 'metadata.json') {
      const md = JSON.parse(body.toString('utf8'));
      for (const k of [...VOLATILE_METADATA, 'rdr_url', 'cache_hash']) delete md[k];
      body = JSON.stringify(md, Object.keys(md).sort());
    }
    return `${f} ${createHash('sha256').update(body).digest('hex')}\n`;
  });
  const tiles = files.filter((f) => /^image_files\//.test(f));
  return { files: files.length, tiles: tiles.length, sha256: createHash('sha256').update(lines.join('')).digest('hex') };
}
export type TreeDigest = ReturnType<typeof treeDigest>;

// --- legacy test/sha1.sh, ported exactly ---

/** Python's float repr, as json.dump writes it. */
function pyFloat(n: number): string {
  const a = Math.abs(n);
  if (a !== 0 && (a >= 1e16 || a < 1e-4)) return n.toExponential().replace(/e([+-])(\d)$/, 'e$10$2');
  return Number.isInteger(n) ? n.toFixed(1) : String(n);
}

type Raw = { raw: string };
/** test/filter-metadata.py: drop the volatile fields, json.dump(sort_keys=True, indent=2). */
function legacyMetadata(text: string): string {
  // keep each number's literal so ints and floats print as Python would
  const d = JSON.parse(text, (_k, v, ctx?: { source?: string }) =>
    typeof v === 'number' ? { raw: /[.eE]/.test(ctx?.source ?? '') ? pyFloat(v) : String(v) } : v);
  for (const k of VOLATILE_METADATA) delete d[k];
  const dump = (v: unknown, ind: number): string => {
    const pad = ' '.repeat(ind + 2); const end = ' '.repeat(ind);
    if (v && typeof v === 'object' && 'raw' in (v as object) && Object.keys(v as object).length === 1) return (v as Raw).raw;
    if (Array.isArray(v)) return v.length ? `[\n${v.map((x) => pad + dump(x, ind + 2)).join(',\n')}\n${end}]` : '[]';
    if (v && typeof v === 'object') {
      const keys = Object.keys(v).sort();
      return keys.length ? `{\n${keys.map((k) => `${pad}${JSON.stringify(k)}: ${dump((v as Record<string, unknown>)[k], ind + 2)}`).join(',\n')}\n${end}}` : '{}';
    }
    return JSON.stringify(v).replace(/[\u007f-￿]/g, (c) => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
  };
  return dump(d, 0);
}

/**
 * test/sha1.sh run from `cwd` on `relDir` (e.g. test/cache/<hash>): sha1 of the
 * `shasum -a 1` lines of every file, metadata.json replaced by its filtered
 * copy metadata_persistent.json, in the order `sort -z` gave under the
 * en_US.UTF-8 locale the goldens were recorded with (bytewise, but `_` before
 * every other character).
 */
export function legacyTreeSha1(cwd: string, relDir: string): string {
  const abs = path.join(cwd, relDir);
  const files = walk(abs).map((f) => path.relative(cwd, f).split(path.sep).join('/')).filter((f) => !f.endsWith('/metadata.json'));
  const extra = new Map<string, string>();
  const md = path.join(abs, 'metadata.json');
  if (existsSync(md)) {
    const p = `${relDir}/metadata_persistent.json`;
    extra.set(p, legacyMetadata(readFileSync(md, 'utf8')));
    files.push(p);
  }
  const key = (s: string) => s.replace(/_/g, '\u0001');
  files.sort((a, b) => (key(a) < key(b) ? -1 : key(a) > key(b) ? 1 : 0));
  const sha1 = (b: Buffer | string) => createHash('sha1').update(b).digest('hex');
  return sha1(files.map((f) => `${sha1(extra.get(f) ?? readFileSync(path.join(cwd, f)))}  ${f}\n`).join(''));
}

/** Checks a finished tile tree against its DZI: every level and every tile present, with the right size. */
export function expectCompletePyramid(dir: string, opts: { checkSizes?: boolean } = {}) {
  const dzi = parseDzi(readFileSync(path.join(dir, 'image.dzi'), 'utf8'));
  const levs = levels(dzi.width, dzi.height);
  const ext = dzi.format === 'jpeg' ? 'jpg' : dzi.format;
  const found = existsSync(path.join(dir, 'image_files')) ? readdirSync(path.join(dir, 'image_files')).map(Number).sort((a, b) => a - b) : [];
  expect(found, 'pyramid levels').toEqual(levs.map((l) => l.level));
  for (const l of levs) {
    const have = readdirSync(path.join(dir, 'image_files', String(l.level))).map((f) => f.replace(/\.[^.]+$/, '')).sort();
    expect(have, `tiles at level ${l.level}`).toEqual(tileGrid(l.width, l.height, dzi.tileSize));
    if (opts.checkSizes) {
      for (const t of have) {
        const [c, r] = t.split('_').map(Number);
        const d = decode(readFileSync(path.join(dir, 'image_files', String(l.level), `${t}.${ext}`)));
        const want = tileSize(dzi, l.width, l.height, c, r);
        expect([d.width, d.height], `size of level ${l.level} tile ${t}`).toEqual([want.width, want.height]);
      }
    }
  }
  for (const f of ['thumbnail.png', 'metadata.json']) expect(existsSync(path.join(dir, f)), `${f} written`).toBe(true);
  return dzi;
}
