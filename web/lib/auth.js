import crypto from 'node:crypto';

const SESSION_HOURS = 12;
const MAX_TRIES = 5;
const BASE_LOCK_MINUTES = 15;

export function hashPasscode(passcode, salt = crypto.randomBytes(16).toString('hex')) {
  const hash = crypto.scryptSync(String(passcode), salt, 32).toString('hex');
  return { salt, hash };
}

export function checkPasscode(profile, passcode) {
  const { hash } = hashPasscode(passcode, profile.salt);
  return safeEqual(hash, profile.passcodeHash);
}

export function safeEqual(a, b) {
  const x = Buffer.from(String(a ?? ''));
  const y = Buffer.from(String(b ?? ''));
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

export const isPasscode = (p) => /^\d{4}$/.test(String(p ?? ''));

/** Milliseconds until the profile unlocks, or 0. */
export function lockRemaining(profile, now = Date.now()) {
  return Math.max(0, (profile.lockedUntil || 0) - now);
}

/** Records a wrong passcode. Every MAX_TRIES misses locks it, doubling each time. */
export function registerFailure(profile, now = Date.now()) {
  profile.failedAttempts = (profile.failedAttempts || 0) + 1;
  if (profile.failedAttempts >= MAX_TRIES) {
    profile.lockCount = (profile.lockCount || 0) + 1;
    profile.lockedUntil = now + BASE_LOCK_MINUTES * 60_000 * 2 ** (profile.lockCount - 1);
    profile.failedAttempts = 0;
  }
  return MAX_TRIES - profile.failedAttempts;
}

export function registerSuccess(profile) {
  profile.failedAttempts = 0;
  profile.lockCount = 0;
  profile.lockedUntil = 0;
}

function signingKey() {
  return crypto.createHash('sha256').update('session:' + (process.env.ENCRYPTION_KEY || '')).digest();
}

/** Session token = base64url(payload).hmac. `ver` lets a passcode change revoke old sessions. */
export function issueSession(profile) {
  const payload = Buffer.from(JSON.stringify({
    pid: profile.id, ver: profile.sessionVersion || 0, exp: Date.now() + SESSION_HOURS * 3600_000,
  })).toString('base64url');
  const mac = crypto.createHmac('sha256', signingKey()).update(payload).digest('base64url');
  return `${payload}.${mac}`;
}

export function readSession(req) {
  const header = req.headers.authorization || '';
  const token = header.startsWith('Bearer ') ? header.slice(7) : '';
  const [payload, mac] = token.split('.');
  if (!payload || !mac) return null;
  const expected = crypto.createHmac('sha256', signingKey()).update(payload).digest('base64url');
  if (!safeEqual(mac, expected)) return null;
  try {
    const data = JSON.parse(Buffer.from(payload, 'base64url').toString('utf8'));
    return data.exp > Date.now() ? data : null;
  } catch {
    return null;
  }
}

export function isSyncRequest(req) {
  const header = req.headers.authorization || '';
  const expected = process.env.SYNC_TOKEN;
  return Boolean(expected) && safeEqual(header, `Bearer ${expected}`);
}
