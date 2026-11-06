/**
 * Copy the DSH web frontend out of the installed asar into the Android app so
 * the WebView can load its JS/CSS from the APK instead of the relay.
 *
 * Usage: node tools/sync-web-assets.mjs [path-to-app.asar]
 * Default asar: D:/dsh/resources/app.asar
 *
 * The desktop's static server sends no cache headers, so without this bundle
 * every launch re-downloads ~5 MB through the encrypted tunnel.
 */

import fs from 'node:fs';
import path from 'node:path';

const asarPath = process.argv[2] ?? 'D:/dsh/resources/app.asar';
const prefix = '/dsh/node_modules/@deepseek-ai/dsh-web-frontend/dist/';
const target = path.resolve('app/src/main/assets/dsh-web');

const fd = fs.openSync(asarPath, 'r');
const head = Buffer.alloc(16);
fs.readSync(fd, head, 0, 16, 0);
const headerSize = head.readUInt32LE(4);
const jsonSize = head.readUInt32LE(12);
const json = Buffer.alloc(jsonSize);
fs.readSync(fd, json, 0, jsonSize, 16);
const root = JSON.parse(json.toString('utf8'));
const base = 8 + headerSize;

const entries = [];
(function walk(node, current) {
  for (const [name, value] of Object.entries(node.files || {})) {
    const entryPath = `${current}/${name}`;
    if (value.files) walk(value, entryPath);
    else entries.push([entryPath, value]);
  }
})(root, '');

const wanted = entries.filter(([entryPath]) => entryPath.startsWith(prefix));
let written = 0;
let bytes = 0;
fs.rmSync(target, { recursive: true, force: true });
for (const [entryPath, value] of wanted) {
  const relative = entryPath.slice(prefix.length);
  if (relative.endsWith('.txt') || relative.endsWith('.map')) continue;
  if (!/^(assets\/|favicon|manifest\.webmanifest)/.test(relative)) continue;
  if (!Number.isFinite(Number(value.offset))) continue;
  const destination = path.join(target, relative);
  fs.mkdirSync(path.dirname(destination), { recursive: true });
  const data = Buffer.alloc(value.size);
  fs.readSync(fd, data, 0, value.size, base + Number(value.offset));
  fs.writeFileSync(destination, data);
  written += 1;
  bytes += value.size;
}
fs.closeSync(fd);
console.log(`copied ${written} files, ${(bytes / 1048576).toFixed(1)} MB -> ${target}`);
