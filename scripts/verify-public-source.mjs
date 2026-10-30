import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { auditBehavior } from './verify-behavior.mjs';

const files = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean);
assert(files.length > 0, 'No tracked source files found');
const forbiddenExtension = /\.(?:jpe?g|gif|webp|bmp|svg|mp4|mov|webm|blend|glb|gltf|fbx|apk|aab|apks|zip|7z|rar|jks|keystore|pem|key|p12|pfx)$/i;
const forbiddenDirectory = /(^|\/)(?:media-source|backups|dist|\.tooling|\.gradle|\.kotlin|__pycache__|build)(\/|$)/;
const manifest = JSON.parse(await readFile('docs/asset-manifest.json', 'utf8'));
const framePaths = Object.keys(manifest);
const launcherManifest = JSON.parse(await readFile('docs/launcher-manifest.json', 'utf8'));
const launcherPaths = Object.keys(launcherManifest);
assert.equal(launcherPaths.length, 15, 'Expected the confirmed launcher exports in five densities');
assert(launcherPaths.every(path => /^app\/src\/main\/res\/mipmap-(?:mdpi|hdpi|xhdpi|xxhdpi|xxxhdpi)\/ic_launcher(?:_round|_foreground)?\.png$/.test(path)), 'Unexpected launcher path');
const reviewedPng = { ...manifest, ...launcherManifest };
assert.equal(framePaths.length, 86, 'Expected the 86 reviewed runtime frames');
assert(framePaths.every(path => /^app\/src\/main\/assets\/naiwa\/[a-z_]+\/frame\d+\.png$/.test(path)), 'Unexpected runtime frame path');
const noticePaths = new Set(['app/src/main/assets/naiwa/notices/materials.txt']);
assert(framePaths.every(path => files.includes(path)), 'All reviewed frames must be tracked');
assert(launcherPaths.every(path => files.includes(path)), 'All confirmed launcher exports must be tracked');
assert.equal(files.filter(path => /\.png$/i.test(path)).length, framePaths.length + launcherPaths.length, 'Unexpected PNG outside reviewed manifests');
const secretPatterns = [
  /(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})/,
  /(?:AKIA|ASIA)[A-Z0-9]{16}/,
  /AIza[0-9A-Za-z_-]{30,}/,
  /-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----/,
];
for (const path of files) {
  assert(!forbiddenExtension.test(path), `Excluded media, archive or key file: ${path}`);
  assert(!forbiddenDirectory.test(path), `Excluded local directory: ${path}`);
  assert(!/(^|\/)(?:\.env(?:\..*)?|local\.properties|key\.properties|keystore\.properties|signing\.properties)$/.test(path), `Local configuration: ${path}`);
  if (/^app\/src\/main\/assets\/naiwa\/[^/]+\//.test(path)) {
    assert(Object.hasOwn(manifest, path) || noticePaths.has(path), `Unreviewed asset or private archive: ${path}`);
  }
  if (path === 'gradle/wrapper/gradle-wrapper.jar') continue;
  const bytes = await readFile(path);
  if (Object.hasOwn(reviewedPng, path)) {
    assert.equal(createHash('sha256').update(bytes).digest('hex'), reviewedPng[path], `Reviewed PNG changed: ${path}`);
    assert(bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])), `Invalid PNG: ${path}`);
    continue;
  }
  assert(!bytes.includes(0), `Unexpected binary file: ${path}`);
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  assert(!secretPatterns.some(pattern => pattern.test(text)), `Potential credential: ${path}`);
  assert(!/[A-Z]:[\\/](?:Users|奶娃桌宠)[\\/]/i.test(text), `Personal local path: ${path}`);
}
const behavior = JSON.parse(await readFile('app/src/main/assets/naiwa/behavior.json', 'utf8'));
assert.equal(behavior.schemaVersion, 2);
const report = await auditBehavior('app/src/main/assets/naiwa');
const approved = report.actions.filter(action => action.displayEligible);
assert.equal(approved.length, 14, 'Expected 14 verified display actions');
assert.equal(approved.reduce((sum, action) => sum + action.frames, 0), 86, 'Expected 86 verified frames');
assert(approved.some(action => action.action === 'idle'), 'Idle must be available');
assert.equal(report.actions.filter(action => action.materialStatus === 'missing').length, 10);
const workflow = await readFile('.github/workflows/android-build.yml', 'utf8');
assert(!/app\/build\/outputs|releases\/upload|gh release|softprops\/action-gh-release/.test(workflow), 'Workflow must not publish APKs or Releases');
console.log(`Publication verified: ${files.length} tracked files, 14 actions / 86 reviewed frames, 15 confirmed launcher PNGs, 10 missing actions, no reference media, signing keys or APKs.`);
