import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFile } from 'node:fs/promises';

const files = execFileSync('git', ['ls-files', '-z'], { encoding: 'utf8' }).split('\0').filter(Boolean);
assert(files.length > 0, 'No tracked source files found');
const forbiddenExtension = /\.(?:png|jpe?g|gif|webp|bmp|svg|mp4|mov|webm|blend|glb|gltf|fbx|apk|aab|apks|zip|7z|rar|jks|keystore|pem|key|p12|pfx)$/i;
const forbiddenDirectory = /(^|\/)(?:media-source|backups|dist|\.tooling|\.gradle|\.kotlin|__pycache__|build)(\/|$)/;
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
  assert(!/^app\/src\/main\/assets\/naiwa\/[^/]+\//.test(path), `Character material or private provenance: ${path}`);
  if (path === 'gradle/wrapper/gradle-wrapper.jar') continue;
  const bytes = await readFile(path);
  assert(!bytes.includes(0), `Unexpected binary file: ${path}`);
  const text = new TextDecoder('utf-8', { fatal: true }).decode(bytes);
  assert(!secretPatterns.some(pattern => pattern.test(text)), `Potential credential: ${path}`);
  assert(!/[A-Z]:[\\/](?:Users|奶娃桌宠)[\\/]/i.test(text), `Personal local path: ${path}`);
}
const behavior = JSON.parse(await readFile('app/src/main/assets/naiwa/behavior.json', 'utf8'));
assert.equal(behavior.schemaVersion, 2);
for (const [action, rule] of Object.entries(behavior.actions)) {
  assert.equal(rule.materialStatus, 'missing', `${action}: material must remain absent in public source`);
  assert.equal(rule.qualityApproved, false, `${action}: private approval must not be published`);
  assert.deepEqual(rule.sha256, {}, `${action}: private frame fingerprints must be omitted`);
  assert(!rule.source, `${action}: private provenance must be omitted`);
}
const animations = JSON.parse(await readFile('app/src/main/assets/naiwa/animations.json', 'utf8'));
assert(Object.values(animations.actions).every(action => !action.source), 'Private animation provenance must be omitted');
const workflow = await readFile('.github/workflows/android-build.yml', 'utf8');
assert(!/app\/build\/outputs|releases\/upload|gh release|softprops\/action-gh-release/.test(workflow), 'Workflow must not publish APKs or Releases');
console.log(`Public source verified: ${files.length} tracked files, ${Object.keys(behavior.actions).length} missing-material actions, no character media or signing keys.`);
