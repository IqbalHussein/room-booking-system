import { handler, method, send, body } from '../lib/http.js';
import { listProfiles, saveProfile, newId } from '../lib/store.js';
import { hashPasscode, isPasscode, safeEqual, issueSession } from '../lib/auth.js';
import { emptySchedule } from '../lib/schedule.js';
import { cleanName, cleanUsername, toClient } from '../lib/profiles.js';

const MAX_PROFILES = 25;

export default handler(async (req, res) => {
  if (!method(req, res, ['GET', 'POST'])) return;
  const profiles = await listProfiles();

  if (req.method === 'GET') {
    return send(res, 200, { profiles: profiles.map((p) => ({ id: p.id, name: p.name })) });
  }

  const b = body(req);
  if (!process.env.INVITE_CODE || !safeEqual(String(b.inviteCode || '').trim(), process.env.INVITE_CODE)) {
    return send(res, 403, { error: "That invite code isn't right. Ask whoever shared this page." });
  }
  const name = cleanName(b.name);
  if (!name) return send(res, 400, { error: 'Pick a name between 1 and 24 characters.' });
  if (profiles.some((p) => p.name.toLowerCase() === name.toLowerCase())) {
    return send(res, 409, { error: `There's already a profile called ${name}.` });
  }
  if (!isPasscode(b.passcode)) return send(res, 400, { error: 'The passcode must be exactly 4 digits.' });
  const username = cleanUsername(b.username);
  const password = String(b.password || '');
  if (!username || !password) return send(res, 400, { error: 'Enter your Carleton username and password.' });
  if (profiles.length >= MAX_PROFILES) return send(res, 400, { error: 'This scheduler is full.' });

  const { salt, hash } = hashPasscode(b.passcode);
  const profile = await saveProfile({
    id: newId(),
    name,
    salt,
    passcodeHash: hash,
    sessionVersion: 0,
    failedAttempts: 0,
    lockedUntil: 0,
    credentials: { username, password },
    paused: false,
    schedule: emptySchedule(),
    lastResults: [],
    createdAt: new Date().toISOString(),
  });
  return send(res, 201, { token: issueSession(profile), profile: toClient(profile) });
});
