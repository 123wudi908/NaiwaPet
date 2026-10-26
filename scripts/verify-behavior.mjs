import { readFile, readdir, writeFile } from 'node:fs/promises';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';
import assert from 'node:assert/strict';
import { actionIds } from './action-ids.mjs';

const known = actionIds;
export async function auditBehavior(root) {
  const policyBytes = await readFile(join(root, 'behavior.json'));
  assert(policyBytes.length <= 262144, 'behavior.json exceeds 256KiB');
  const policy = JSON.parse(policyBytes);
  const animations = JSON.parse(await readFile(join(root, 'animations.json'), 'utf8'));
  assert.equal(policy.schemaVersion, 2);
  const automatic = policy.automaticBehavior, movement = policy.automaticMovement;
  for (const [settings, min, max] of [[automatic, 1000, 60000], [movement, 1000, 3600000]]) {
    assert(typeof settings.enabledByDefault === 'boolean');
    assert(Number.isInteger(settings.minIntervalMillis) && settings.minIntervalMillis >= min);
    assert(Number.isInteger(settings.maxIntervalMillis) && settings.maxIntervalMillis >= settings.minIntervalMillis && settings.maxIntervalMillis <= max);
  }
  assert(Number.isFinite(movement.speedDpPerSecond) && movement.speedDpPerSecond >= 1 && movement.speedDpPerSecond <= 200);
  assert(Number.isInteger(policy.interaction.resumeDelayMillis) && policy.interaction.resumeDelayMillis >= 0 && policy.interaction.resumeDelayMillis <= 60000);
  const rows = [];
  for (const [action, rule] of Object.entries(policy.actions)) {
    assert(known.includes(action), `${action}: unknown policy action`);
    assert(Number.isInteger(rule.weight) && rule.weight >= 0 && rule.weight <= 10000, `${action}: invalid weight`);
    assert(['formal', 'temporary', 'missing'].includes(rule.materialStatus), `${action}: invalid material status`);
    for (const field of ['qualityApproved', 'allowAutomatic', 'allowManual']) assert(typeof rule[field] === 'boolean', `${action}: ${field}`);
    assert(rule.sha256 && typeof rule.sha256 === 'object' && !Array.isArray(rule.sha256));
    assert(Number.isInteger(rule.cooldownMillis ?? 0) && (rule.cooldownMillis ?? 0) >= 0 && (rule.cooldownMillis ?? 0) <= 86400000);
    const source = rule.source;
    let authorizationVerified = false;
    if (source && ['private-personal','licensed'].includes(source.usageScope) &&
        typeof source.origin === 'string' && source.origin.trim() &&
        /^naiwa\/notices\/[a-zA-Z0-9_-]+\.txt$/.test(source.evidencePath) && /^[a-fA-F0-9]{64}$/.test(source.evidenceSha256)) {
      try {
        const evidence = await readFile(join(root,source.evidencePath.slice('naiwa/'.length)));
        authorizationVerified = evidence.length > 0 && evidence.length <= 65536 &&
          createHash('sha256').update(evidence).digest('hex') === source.evidenceSha256.toLowerCase();
      } catch(error) { if(error.code !== 'ENOENT') throw error; }
    }
    let files;
    try { files = (await readdir(join(root, action))).filter(name => name.toLowerCase().endsWith('.png')).sort(); }
    catch (error) { if (error.code !== 'ENOENT') throw error; files = []; }
    const clip = animations.actions?.[action];
    const present = Boolean(clip && files.length >= 1 && files.length <= 120);
    const temporary = clip?.temporary ?? animations.placeholder ?? true;
    const actualHashes = {};
    for (const name of files) actualHashes[name] = createHash('sha256').update(await readFile(join(root, action, name))).digest('hex');
    const hashesMatch = present && Object.keys(rule.sha256).length === files.length && files.every(name =>
      typeof rule.sha256[name] === 'string' && /^[0-9a-fA-F]{64}$/.test(rule.sha256[name]) && rule.sha256[name].toLowerCase() === actualHashes[name]);
    const reason = !present || rule.materialStatus === 'missing' ? '缺少素材' :
      rule.materialStatus !== 'formal' || temporary ? '临时素材，不进入自动池' :
      !authorizationVerified ? '缺少与当前使用范围匹配的素材来源记录' :
      !rule.qualityApproved ? '尚未通过正式素材质量验收' :
      !hashesMatch ? '素材校验或已批准哈希不匹配' : !rule.allowAutomatic ? '未明确允许自动使用' :
      action === 'idle' || rule.weight === 0 ? '默认待机或权重为零' : null;
    rows.push({ action, weight: rule.weight, frames: files.length, materialStatus: rule.materialStatus,
      metadataTemporary: temporary, qualityApproved: rule.qualityApproved, allowAutomatic: rule.allowAutomatic,
      manualPreviewAvailable: present && rule.allowManual, approvedHashesMatch: hashesMatch, authorizationVerified,
      usageScope: source?.usageScope ?? 'unverified',
      displayEligible: present && !temporary && rule.materialStatus === 'formal' && rule.qualityApproved && hashesMatch && authorizationVerified,
      automaticEligible: reason === null, reason, frameSha256: actualHashes });
  }
  return { schemaVersion: 2, scope: 'metadata and exact reviewed fingerprints; run verify-assets.mjs for full PNG CRC/alpha validation',
    defaults: { automaticBehavior: automatic.enabledByDefault, automaticMovement: movement.enabledByDefault },
    automaticActions: rows.filter(row => row.automaticEligible).map(row => row.action), actions: rows };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = process.argv[2] ?? 'app/src/main/assets/naiwa';
  const report = await auditBehavior(root);
  for (const row of report.actions) console.log(`${row.action}: ${row.frames} frames, weight=${row.weight}, ${row.materialStatus}, automatic=${row.automaticEligible}, ${row.reason ?? 'approved exact frames'}`);
  console.log(`Automatic pool: ${report.automaticActions.join(', ') || '(empty — no formal approved actions)'}`);
  if (process.argv[3]) await writeFile(process.argv[3], JSON.stringify(report, null, 2)+'\n');
}
