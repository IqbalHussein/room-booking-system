// Profile storage on Vercel Blob. Every record is AES-256-GCM encrypted as a
// whole, so even the blob store never holds a readable password or schedule.
import { put, get, list, del } from '@vercel/blob';
import crypto from 'node:crypto';

const ACCESS = process.env.BLOB_ACCESS === 'public' ? 'public' : 'private';
const PREFIX = 'profiles/';

function key() {
  const raw = process.env.ENCRYPTION_KEY;
  if (!raw) throw new Error('ENCRYPTION_KEY is not configured');
  return crypto.createHash('sha256').update(raw).digest();
}

export function encrypt(obj) {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', key(), iv);
  const body = Buffer.concat([cipher.update(JSON.stringify(obj), 'utf8'), cipher.final()]);
  return Buffer.concat([iv, cipher.getAuthTag(), body]).toString('base64');
}

export function decrypt(text) {
  const buf = Buffer.from(text, 'base64');
  const decipher = crypto.createDecipheriv('aes-256-gcm', key(), buf.subarray(0, 12));
  decipher.setAuthTag(buf.subarray(12, 28));
  return JSON.parse(Buffer.concat([decipher.update(buf.subarray(28)), decipher.final()]).toString('utf8'));
}

const pathFor = (id) => `${PREFIX}${id}.enc`;

async function streamToText(stream) {
  const chunks = [];
  for await (const chunk of stream) chunks.push(Buffer.from(chunk));
  return Buffer.concat(chunks).toString('utf8');
}

/** Returns { profile, etag } or null. The etag enables conditional writes. */
export async function loadProfileVersioned(id) {
  if (!/^[a-z0-9]{8,32}$/.test(String(id || ''))) return null;
  const res = await get(pathFor(id), { access: ACCESS, useCache: false });
  if (!res || res.statusCode !== 200 || !res.stream) return null;
  // A compressed response carries a weak ETag (W/"…"); ifMatch needs the strong form.
  const etag = String(res.blob.etag || '').replace(/^W\//, '');
  return { profile: decrypt(await streamToText(res.stream)), etag };
}

export async function loadProfile(id) {
  return (await loadProfileVersioned(id))?.profile ?? null;
}

/**
 * Saves a profile. With `ifMatch`, the write only succeeds if nobody else
 * wrote in between; otherwise it throws BlobPreconditionFailedError.
 */
export async function saveProfile(profile, { ifMatch } = {}) {
  profile.updatedAt = new Date().toISOString();
  await put(pathFor(profile.id), encrypt(profile), {
    access: ACCESS,
    addRandomSuffix: false,
    allowOverwrite: true,
    cacheControlMaxAge: 60,
    contentType: 'application/octet-stream',
    ...(ifMatch ? { ifMatch } : {}),
  });
  return profile;
}

export async function deleteProfile(id) {
  await del(pathFor(id));
}

export async function listProfiles() {
  const ids = [];
  let cursor;
  do {
    const page = await list({ prefix: PREFIX, cursor, limit: 1000 });
    for (const b of page.blobs) {
      const m = b.pathname.match(/^profiles\/([a-z0-9]+)\.enc$/);
      if (m) ids.push(m[1]);
    }
    cursor = page.hasMore ? page.cursor : undefined;
  } while (cursor);
  const profiles = await Promise.all(ids.map((id) => loadProfile(id).catch(() => null)));
  return profiles.filter(Boolean).sort((a, b) => (a.createdAt || '').localeCompare(b.createdAt || ''));
}

export function newId() {
  return crypto.randomBytes(8).toString('hex');
}

/* ---- run bookkeeping (no personal data: dates, timestamps, statuses) ---- */

/** Returns { data, etag } or null. */
export async function readJson(pathname) {
  const res = await get(pathname, { access: ACCESS, useCache: false });
  if (!res || res.statusCode !== 200 || !res.stream) return null;
  return { data: JSON.parse(await streamToText(res.stream)), etag: String(res.blob.etag || '').replace(/^W\//, '') };
}

/**
 * Writes JSON. `create: true` fails if the file exists; `ifMatch` fails if it
 * changed since it was read. Both throw BlobPreconditionFailedError (or a
 * "blob already exists" BlobError) so callers can treat them as "lost the race".
 */
export async function writeJson(pathname, data, { create = false, ifMatch } = {}) {
  await put(pathname, JSON.stringify(data), {
    access: ACCESS,
    addRandomSuffix: false,
    allowOverwrite: !create,
    cacheControlMaxAge: 60,
    contentType: 'application/json',
    ...(ifMatch ? { ifMatch } : {}),
  });
}
