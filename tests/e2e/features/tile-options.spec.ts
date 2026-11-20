// Tile format, size, overlap and thumbnail height.
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { expect, test } from '../support/test';
import { about, feature, needsReview } from '../support/features';
import { cliTile } from '../support/run';
import { VICAR, freshCopy } from '../support/synthetic';
import { decode, expectCompletePyramid, parseDzi } from '../support/tree';

const S = VICAR['byte_ramp.vic'];

test.describe('Tile format and geometry', feature('tile-options'), () => {
  test('format=jpg writes JPEG tiles and declares them in the DZI', about(
    `Tiles the BYTE ramp with \`format=jpg\`. Passes when the DZI declares \`Format="jpg"\`, the pyramid is complete, and the tiles are JPEG files.`,
    '@nominal',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/byte_ramp.vic&format=jpg');
    expect(t.dzi.format).toBe('jpg');
    expectCompletePyramid(t.dir, { checkSizes: true });
    const d = decode(readFileSync(path.join(t.dir, 'image_files', '0', '0_0.jpg')));
    expect(d.chunks, 'a JPEG, not a PNG').toEqual([]);
  });

  test('tileSize and tileOverlap set the tile grid', about(
    `Tiles the ${S.ns}x${S.nl} BYTE ramp with \`tileSize=128&tileOverlap=0\`. Passes when the DZI declares TileSize 128 and Overlap 0, and every level has ceil(width / 128) x ceil(height / 128) tiles of exactly 128x128 (clipped at the edges).`,
    '@nominal',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/byte_ramp.vic&tileSize=128&tileOverlap=0');
    expect([t.dzi.tileSize, t.dzi.overlap]).toEqual([128, 0]);
    expectCompletePyramid(t.dir, { checkSizes: true });
  });

  test('thumbHeight sets the thumbnail height and keeps the aspect ratio', about(
    `Tiles the ${S.ns}x${S.nl} BYTE ramp with \`thumbHeight=60\`. Passes when thumbnail.png is 60 pixels high and round(60 * ${S.ns} / ${S.nl}) pixels wide.`,
    '@nominal',
  ), async ({ tiler }) => {
    const t = await tiler.tile('image=synthetic/byte_ramp.vic&thumbHeight=60');
    const d = decode(readFileSync(path.join(t.dir, 'thumbnail.png')));
    expect(d.height).toBe(60);
    expect(Math.abs(d.width - (60 * S.ns) / S.nl)).toBeLessThanOrEqual(1);
  });

  test('TileCLI -tf -ts -to -th set format, tile size, overlap and thumbnail height', about(
    `Tiles the BYTE ramp with TileCLI \`-tf jpg -ts 96 -to 1 -th 50\`. Passes when image.dzi declares JPEG tiles of 96 with overlap 1, every level has the implied grid with tiles of the implied size, and the thumbnail is 50 pixels high.`,
    '@nominal',
  ), async ({}, testInfo) => {
    const t = await cliTile(testInfo, freshCopy('byte_ramp.vic', 'cli-options').input, ['-tf', 'jpg', '-ts', '96', '-to', '1', '-th', '50']);
    const dzi = parseDzi(readFileSync(path.join(t.dir, 'image.dzi'), 'utf8'));
    expect([dzi.format, dzi.tileSize, dzi.overlap]).toEqual(['jpg', 96, 1]);
    expectCompletePyramid(t.dir, { checkSizes: true });
    expect(decode(readFileSync(path.join(t.dir, 'thumbnail.png'))).height).toBe(50);
  });

  test('no extra tiles when a level is a whole number of tiles', about(
    `Tiles the ${S.ns}x${S.nl} BYTE ramp with \`tileSize=100&tileOverlap=2\`, so the full-resolution level is exactly 3 tiles high. Passes when every level has exactly the ceil(width / 100) x ceil(height / 100) tiles a Deep Zoom client requests. The tiler currently also writes a fourth row of 2-pixel-high tiles that hold only overlap.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    needsReview('pyramidio TileBuilder', 'with overlap, a level whose size is a multiple of the tile size gets an extra row or column of overlap-only tiles', { expectFail: true });
    const t = await tiler.tile('image=synthetic/byte_ramp.vic&tileSize=100&tileOverlap=2');
    expectCompletePyramid(t.dir);
  });

  test('tile sizes stay exact at every level with tileOverlap=2', about(
    `Tiles the ${S.ns}x${S.nl} BYTE ramp with \`tileSize=96&tileOverlap=2\` (no level is a whole number of tiles). Passes when every tile of every level is exactly the size the DZI geometry implies. Below the full-resolution level some tiles are currently one pixel short.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    needsReview('pyramidio TileBuilder', 'with tileOverlap=2, tiles below the full-resolution level are one pixel short', { expectFail: true });
    const t = await tiler.tile('image=synthetic/byte_ramp.vic&tileSize=96&tileOverlap=2');
    expectCompletePyramid(t.dir, { checkSizes: true });
  });

  test('an unsupported tile format is refused with 400', about(
    `Requests the BYTE ramp with \`format=gif\`. Passes on 400.`,
    '@off-nominal',
  ), async ({ tiler }) => {
    expect((await tiler.get('?image=synthetic/byte_ramp.vic&format=gif')).status()).toBe(400);
  });
});
