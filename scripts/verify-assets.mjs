import { readFile, readdir } from 'node:fs/promises';
import { join } from 'node:path';
import { inflateSync } from 'node:zlib';
import assert from 'node:assert/strict';
import { actionIds } from './action-ids.mjs';

const root = process.argv[2] ?? 'app/src/main/assets/naiwa';
const config = JSON.parse(await readFile(join(root, 'animations.json'), 'utf8'));
function checkLayout(canvas, crop) {
  const { width, height } = canvas;
  assert(Number.isInteger(width) && Number.isInteger(height) && width > 0 && height > 0 && width <= 4096 && height <= 4096);
  assert(crop && ['left', 'top', 'right', 'bottom'].every(key => Number.isInteger(crop[key])));
  assert(crop.left >= 0 && crop.top >= 0 && crop.right <= width && crop.bottom <= height && crop.right > crop.left && crop.bottom > crop.top);
}
checkLayout(config.canvas, config.contentBounds);
const referenceHeight = config.referenceHeight ?? config.contentBounds.bottom-config.contentBounds.top;
assert(Number.isInteger(referenceHeight) && referenceHeight > 0 &&
  referenceHeight <= config.contentBounds.bottom-config.contentBounds.top, 'invalid visible referenceHeight');

const crcTable = Array.from({ length: 256 }, (_, value) => {
  for (let i = 0; i < 8; i++) value = (value & 1) ? 0xedb88320 ^ (value >>> 1) : value >>> 1;
  return value >>> 0;
});
function crc32(data) {
  let crc = 0xffffffff;
  for (const byte of data) crc = crcTable[(crc ^ byte) & 255] ^ (crc >>> 8);
  return (crc ^ 0xffffffff) >>> 0;
}
function paeth(a, b, c) {
  const p = a + b - c;
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
}
function checkPng(bytes, path, canvas, crop) {
  const { width, height } = canvas;
  assert(bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), `${path}: PNG signature`);
  let position = 8, header, ended = false;
  const chunks = [];
  while (position + 12 <= bytes.length) {
    const length = bytes.readUInt32BE(position);
    assert(position + 12 + length <= bytes.length, `${path}: truncated PNG chunk`);
    const type = bytes.toString('ascii', position + 4, position + 8);
    const data = bytes.subarray(position + 8, position + 8 + length);
    assert.equal(crc32(bytes.subarray(position + 4, position + 8 + length)), bytes.readUInt32BE(position + 8 + length), `${path}: ${type} CRC`);
    if (type === 'IHDR') header = data;
    if (type === 'IDAT') chunks.push(data);
    position += 12 + length;
    if (type === 'IEND') { ended = true; break; }
  }
  assert(header && ended && chunks.length, `${path}: missing PNG chunks`);
  assert.equal(header.readUInt32BE(0), width, `${path}: canvas width`);
  assert.equal(header.readUInt32BE(4), height, `${path}: canvas height`);
  assert(header[8] === 8 && header[9] === 6 && header[12] === 0, `${path}: use 8-bit RGBA, non-interlaced PNG`);
  const stride = width * 4;
  const raw = inflateSync(Buffer.concat(chunks), { maxOutputLength: (stride + 1) * height });
  assert.equal(raw.length, (stride + 1) * height, `${path}: decoded bytes`);
  let previous = Buffer.alloc(stride), transparent = 0, visible = 0;
  for (let y = 0; y < height; y++) {
    const row = Buffer.from(raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)));
    const filter = raw[y * (stride + 1)];
    assert(filter <= 4, `${path}: invalid PNG filter`);
    for (let i = 0; i < stride; i++) {
      const a = i >= 4 ? row[i - 4] : 0, b = previous[i], c = i >= 4 ? previous[i - 4] : 0;
      const prediction = filter === 0 ? 0 : filter === 1 ? a : filter === 2 ? b : filter === 3 ? Math.floor((a + b) / 2) : paeth(a, b, c);
      row[i] = (row[i] + prediction) & 255;
    }
    for (let x = 0; x < width; x++) {
      if (row[x * 4 + 3] === 0) transparent++;
      else {
        visible++;
        assert(x >= crop.left && x < crop.right && y >= crop.top && y < crop.bottom, `${path}: visible pixel outside shared contentBounds`);
      }
    }
    previous = row;
  }
  assert(transparent > 0 && visible > 0, `${path}: needs visible content and transparent background`);
}

let total = 0;
const requiredActions = ['idle'];
for (const action of requiredActions) assert(config.actions[action], `${action}: required action is missing`);
for (const [action, settings] of Object.entries(config.actions)) {
  assert(actionIds.includes(action), `${action}: unknown action`);
  assert(Number.isInteger(settings.fps) && settings.fps >= 1 && settings.fps <= 30 && typeof settings.loop === 'boolean', `${action}: playback settings`);
  if (settings.interruptible !== undefined) assert.equal(typeof settings.interruptible,'boolean');
  const hold = settings.lastFrameHoldMillis ?? 0;
  assert(Number.isInteger(hold) && hold >= 0 && hold <= 10000 && (hold === 0 || !settings.loop), `${action}: invalid lastFrameHoldMillis`);
  if (settings.temporary !== undefined) assert(typeof settings.temporary === 'boolean', `${action}: temporary must be boolean`);
  assert(action === 'idle' || (action === 'walk' ? settings.loop : !settings.loop), `${action}: only idle/walk may loop and walk must loop`);
  const canvas = settings.canvas ?? config.canvas;
  const crop = settings.contentBounds ?? config.contentBounds;
  assert(Boolean(settings.canvas) === Boolean(settings.contentBounds), `${action}: canvas and contentBounds must be specified together`);
  checkLayout(canvas, crop);
  const files = (await readdir(join(root, action))).filter(name => name.toLowerCase().endsWith('.png'));
  assert(files.length >= 1 && files.length <= 120, `${action}: expected 1–120 PNGs`);
  if (settings.frameDurationsMillis !== undefined) {
    assert(Array.isArray(settings.frameDurationsMillis) && settings.frameDurationsMillis.length === files.length &&
      settings.frameDurationsMillis.every(n=>Number.isInteger(n) && n >= 16 && n <= 10000), `${action}: invalid per-frame durations`);
  }
  for (const file of files) checkPng(await readFile(join(root, action, file)), `${action}/${file}`, canvas, crop);
  total += files.length;
  console.log(`${action}: ${files.length} RGBA PNG frames, ${settings.fps} fps, loop=${settings.loop}, ${canvas.width}x${canvas.height}, lastFrameHoldMillis=${hold}, temporary=${settings.temporary ?? config.placeholder}`);
}
console.log(`PASS: ${total} PNGs, valid CRC, shared crop within each action, transparent background. placeholder=${config.placeholder}`);
