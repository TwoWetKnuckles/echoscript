export const VERSION = '0.1.1';
export const MAX_BYTES = 128 * 1024 * 1024;
export async function hashFile(file) {
  if (!file || file.size === 0) throw Error('Select a non-empty file.');
  if (file.size > MAX_BYTES) throw Error('This development build supports files up to 128 MiB.');
  if (!globalThis.crypto?.subtle) throw Error('SHA-256 requires a secure browser context (HTTPS or localhost).');
  const bytes = await file.arrayBuffer();
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))].map(x=>x.toString(16).padStart(2,'0')).join('');
}
export function makeManifest(file, sha256, now = new Date()) {
  return {schema:'echoscript.intake.v1',software:{name:'EchoScript',version:VERSION},recordedAtUtc:now.toISOString(),clockSource:'device clock; not independently attested',artifact:{type:'SOURCE',filename:file.name,bytes:file.size,mimeType:file.type || 'unknown',sha256},operation:'READ_AND_HASH',sourceModified:false,limitations:['Basic intake record; not a complete chain of custody.','No encrypted vault or digital signature.','File bytes remain in browser memory until released; downloads are not encrypted.']};
}
export function checkManifest(manifest, file, hash) {
  if(manifest?.schema !== 'echoscript.intake.v1' || !/^[a-f0-9]{64}$/.test(manifest?.artifact?.sha256 || '') || !Number.isSafeInteger(manifest?.artifact?.bytes) || manifest.artifact.bytes < 1) throw Error('Unsupported or invalid EchoScript intake manifest.');
  return {match:manifest.artifact.sha256 === hash && manifest.artifact.bytes === file.size,filenameMatches:manifest.artifact.filename === file.name};
}
