// Synthetic inputs written by the suite itself: VICAR images with a closed-form
// pixel pattern (like VICAR `gen`), PNG and JPEG gradients, and VICAR labels with
// known contents. Expected tile pixels and stretch limits are computed from the
// same formulas, so no recorded golden is needed for them.
import { copyFileSync, mkdirSync, symlinkSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import jpeg from 'jpeg-js';
import { PNG } from 'pngjs';
import { SYNTH_DIR } from './env';

export type VicarFormat = 'BYTE' | 'HALF' | 'FULL' | 'REAL';

/** A generated VICAR image: pixel(l, s, b) = base + l*linc + s*sinc + b*binc, cast to the format. */
export interface VicarSpec {
  nl: number; ns: number; nb: number; format: VicarFormat;
  linc: number; sinc: number; binc: number; base?: number;
  /** Extra label items after the system label, e.g. PROPERTY and TASK groups. */
  label?: string;
}

const BYTES: Record<VicarFormat, number> = { BYTE: 1, HALF: 2, FULL: 4, REAL: 4 };

/** The stored value of a pixel, after the cast to the image's data type. */
export function value(s: VicarSpec, line: number, samp: number, band = 0): number {
  const v = (s.base ?? 0) + line * s.linc + samp * s.sinc + band * s.binc;
  switch (s.format) {
    case 'BYTE': return ((Math.trunc(v) % 256) + 256) % 256;
    case 'HALF': return (Math.trunc(v) << 16) >> 16;
    case 'FULL': return Math.trunc(v) | 0;
    default: return Math.fround(v);
  }
}

/** Minimum and maximum stored value over the whole image. */
export function extrema(s: VicarSpec): [number, number] {
  let lo = Infinity; let hi = -Infinity;
  for (let b = 0; b < s.nb; b++) for (let l = 0; l < s.nl; l++) for (let x = 0; x < s.ns; x++) {
    const v = value(s, l, x, b);
    if (v < lo) lo = v;
    if (v > hi) hi = v;
  }
  return [lo, hi];
}

/** A big-endian BSQ VICAR file for the spec: system label, optional extra label items, pixels. */
export function vicarBytes(s: VicarSpec): Buffer {
  const recsize = s.ns * BYTES[s.format];
  const items = (lblsize: number) => [
    `LBLSIZE=${lblsize}`, `FORMAT='${s.format}'`, `TYPE='IMAGE'`, `BUFSIZ=${recsize}`, 'DIM=3', 'EOL=0',
    `RECSIZE=${recsize}`, `ORG='BSQ'`, `NL=${s.nl}`, `NS=${s.ns}`, `NB=${s.nb}`, `N1=${s.ns}`, `N2=${s.nl}`, `N3=${s.nb}`,
    'N4=0', 'NBB=0', 'NLB=0', `HOST='JAVA'`, `INTFMT='HIGH'`, `REALFMT='IEEE'`, `BHOST='JAVA'`, `BINTFMT='HIGH'`,
    `BREALFMT='IEEE'`, `BLTYPE=''`,
  ].join('  ') + (s.label ? `  ${s.label.trim()}` : '') + '  ';
  // LBLSIZE is a whole number of records and must leave room for itself
  let lblsize = recsize;
  while (items(lblsize).length > lblsize) lblsize += recsize;
  const label = Buffer.alloc(lblsize, ' ');
  label.write(items(lblsize), 'latin1');
  const pixels = Buffer.alloc(recsize * s.nl * s.nb);
  let o = 0;
  for (let b = 0; b < s.nb; b++) for (let l = 0; l < s.nl; l++) for (let x = 0; x < s.ns; x++) {
    const v = value(s, l, x, b);
    switch (s.format) {
      case 'BYTE': pixels.writeUInt8(v, o); break;
      case 'HALF': pixels.writeInt16BE(v, o); break;
      case 'FULL': pixels.writeInt32BE(v, o); break;
      default: pixels.writeFloatBE(v, o);
    }
    o += BYTES[s.format];
  }
  return Buffer.concat([label, pixels]);
}

/** The generated VICAR images, by file name under .cache/synthetic. */
export const VICAR: Record<string, VicarSpec> = {
  // 8-bit ramp that wraps every 256 counts: every tile has structure
  'byte_ramp.vic': { nl: 300, ns: 520, nb: 1, format: 'BYTE', linc: 1, sinc: 1, binc: 0 },
  // 12-bit-like ramp: stretch limits are known exactly (min 5, max 5 + 199*10 + 299*3)
  'half_ramp.vic': { nl: 200, ns: 300, nb: 1, format: 'HALF', linc: 10, sinc: 3, binc: 0, base: 5 },
  // color: three bands offset by 80 counts
  'byte_rgb.vic': { nl: 128, ns: 160, nb: 3, format: 'BYTE', linc: 1, sinc: 0.5, binc: 80 },
  'real_ramp.vic': { nl: 100, ns: 140, nb: 1, format: 'REAL', linc: 0.5, sinc: 0.25, binc: 0, base: -10 },
  'full_ramp.vic': { nl: 64, ns: 96, nb: 1, format: 'FULL', linc: 1000, sinc: 7, binc: 0 },
  // VICAR labels with property groups and history tasks, for the finder label endpoint
  'labeled.vic': {
    nl: 8, ns: 16, nb: 1, format: 'BYTE', linc: 1, sinc: 1, binc: 0,
    label: `PROPERTY='IDENTIFICATION'  MISSION_NAME='SYNTHETIC'  INSTRUMENT_ID='TESTCAM'  FILTER_NUMBER=(1,2,3)  `
      + `PROPERTY='CAMERA'  EXPOSURE=1.5  `
      + `TASK='GEN'  USER='tester'  DAT_TIM='Mon Nov  2 00:00:00 2026'  IVAL=7  `
      + `TASK='STRETCH'  USER='tester'  DAT_TIM='Mon Nov  2 00:01:00 2026'  LIMITS=(0,255)  `,
  },
  'properties_only.vic': {
    nl: 8, ns: 16, nb: 1, format: 'BYTE', linc: 1, sinc: 1, binc: 0,
    label: `PROPERTY='IDENTIFICATION'  MISSION_NAME='SYNTHETIC'  PROPERTY='CAMERA'  EXPOSURE=1.5  `,
  },
};

/** Pixel value of the RGB gradient PNG/JPEG at (x, y). */
export const rgbAt = (x: number, y: number): [number, number, number] => [x % 256, y % 256, (x + y) % 256];
export const GRADIENT = { width: 300, height: 220 };

function gradientPng(): Buffer {
  const png = new PNG({ width: GRADIENT.width, height: GRADIENT.height, colorType: 2 });
  for (let y = 0; y < GRADIENT.height; y++) for (let x = 0; x < GRADIENT.width; x++) {
    const i = (y * GRADIENT.width + x) * 4;
    const [r, g, b] = rgbAt(x, y);
    png.data[i] = r; png.data[i + 1] = g; png.data[i + 2] = b; png.data[i + 3] = 255;
  }
  return PNG.sync.write(png, { colorType: 2 });
}

function gradientJpeg(): Buffer {
  const data = Buffer.alloc(GRADIENT.width * GRADIENT.height * 4);
  for (let y = 0; y < GRADIENT.height; y++) for (let x = 0; x < GRADIENT.width; x++) {
    const i = (y * GRADIENT.width + x) * 4;
    const [r, g, b] = rgbAt(x, y);
    data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = 255;
  }
  return Buffer.from(jpeg.encode({ data, width: GRADIENT.width, height: GRADIENT.height }, 95).data);
}

/** Writes every synthetic input into .cache/synthetic. Deterministic: same bytes on every run. */
export function generateSynthetic(dir = SYNTH_DIR) {
  mkdirSync(dir, { recursive: true });
  for (const [name, spec] of Object.entries(VICAR)) writeFileSync(path.join(dir, name), vicarBytes(spec));
  writeFileSync(path.join(dir, 'gradient.png'), gradientPng());
  writeFileSync(path.join(dir, 'gradient.jpg'), gradientJpeg());
  // not an image at all, under an image extension
  writeFileSync(path.join(dir, 'not_an_image.vic'), 'This is plain text, not a VICAR label.\n'.repeat(20));
  // a VICAR label that promises more pixels than the file holds
  writeFileSync(path.join(dir, 'truncated.vic'), vicarBytes(VICAR['byte_ramp.vic']).subarray(0, 4000));
  writeFileSync(path.join(dir, 'unsupported.xyz'), 'not an image\n');
}

/**
 * A private copy of a synthetic input under a unique name, so a test can tile
 * it from scratch (an uncached image) without racing other tests. Returns the
 * servlet image path (relative to fs_input_dir) and the TileCLI input path.
 */
export function freshCopy(name: string, tag: string) {
  const dir = path.join(SYNTH_DIR, 'fresh');
  mkdirSync(dir, { recursive: true });
  const ext = path.extname(name);
  const file = `${path.basename(name, ext)}_${tag.replace(/[^A-Za-z0-9]+/g, '_').slice(0, 40)}_${Date.now().toString(36)}${ext}`;
  copyFileSync(path.join(SYNTH_DIR, name), path.join(dir, file));
  return { image: `synthetic/fresh/${file}`, input: `test/synthetic/fresh/${file}` };
}

/** Like freshCopy, for a large file: a uniquely named symbolic link to it under synthetic/fresh. */
export function freshLink(target: string, tag: string) {
  const dir = path.join(SYNTH_DIR, 'fresh');
  mkdirSync(dir, { recursive: true });
  const name = path.basename(target);
  const sub = `${tag.replace(/[^A-Za-z0-9]+/g, '_').slice(0, 40)}_${Date.now().toString(36)}`;
  mkdirSync(path.join(dir, sub));
  symlinkSync(target, path.join(dir, sub, name));
  return { image: `synthetic/fresh/${sub}/${name}`, input: `test/synthetic/fresh/${sub}/${name}` };
}
