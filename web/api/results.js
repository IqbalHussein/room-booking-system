import { handler, method, send, body } from '../lib/http.js';
import { loadProfile, saveProfile } from '../lib/store.js';
import { isSyncRequest } from '../lib/auth.js';

const KEEP = 14;
const STATUSES = new Set(['SUCCESS', 'SKIPPED', 'UNAVAILABLE', 'FAILED']);
const clip = (s, n) => (s == null ? null : String(s).slice(0, n));

// The nightly job reports each profile's outcome so people can see it in the UI.
export default handler(async (req, res) => {
  if (!method(req, res, ['POST'])) return;
  if (!isSyncRequest(req)) return send(res, 401, { error: 'Unauthorized.' });
  const b = body(req);
  const results = Array.isArray(b.results) ? b.results : [];
  const ranAt = new Date().toISOString();
  let stored = 0;
  for (const r of results) {
    if (!STATUSES.has(r.status)) continue;
    const profile = await loadProfile(r.profileId);
    if (!profile) continue;
    profile.lastResults = [
      { ranAt, targetDate: clip(b.targetDate, 10), status: r.status, summary: clip(r.summary, 300), detail: clip(r.detail, 600) },
      ...(profile.lastResults || []),
    ].slice(0, KEEP);
    await saveProfile(profile);
    stored++;
  }
  return send(res, 200, { stored });
});
