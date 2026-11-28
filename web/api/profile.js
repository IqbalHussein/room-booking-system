import { handler, method, send, body } from '../lib/http.js';
import { loadProfile, saveProfile, deleteProfile, listProfiles } from '../lib/store.js';
import { readSession, hashPasscode, isPasscode, checkPasscode, issueSession } from '../lib/auth.js';
import { validateSchedule, claimsOf, findConflicts } from '../lib/schedule.js';
import { cleanName, cleanUsername, toClient } from '../lib/profiles.js';

export default handler(async (req, res) => {
  if (!method(req, res, ['GET', 'PUT', 'DELETE'])) return;
  const session = readSession(req);
  const profile = session && (await loadProfile(session.pid));
  if (!profile || (profile.sessionVersion || 0) !== session.ver) {
    return send(res, 401, { error: 'Your session ended. Enter your passcode again.' });
  }

  if (req.method === 'GET') return send(res, 200, { profile: toClient(profile) });

  if (req.method === 'DELETE') {
    await deleteProfile(profile.id);
    return send(res, 200, { deleted: true });
  }

  const b = body(req);
  const all = await listProfiles();
  let token;

  if (b.name !== undefined) {
    const name = cleanName(b.name);
    if (!name) return send(res, 400, { error: 'Pick a name between 1 and 24 characters.' });
    if (all.some((p) => p.id !== profile.id && p.name.toLowerCase() === name.toLowerCase())) {
      return send(res, 409, { error: `There's already a profile called ${name}.` });
    }
    profile.name = name;
  }
  if (b.schedule !== undefined) {
    const { schedule, error } = validateSchedule(b.schedule, profile.schedule);
    if (error) return send(res, 400, { error });
    profile.schedule = schedule;
  }
  if (b.paused !== undefined) profile.paused = Boolean(b.paused);
  if (b.username !== undefined || b.password !== undefined) {
    const username = cleanUsername(b.username ?? profile.credentials?.username);
    const password = b.password ? String(b.password) : profile.credentials?.password || '';
    if (!username || !password) return send(res, 400, { error: 'Enter both your Carleton username and password.' });
    profile.credentials = { username, password };
  }
  if (b.newPasscode !== undefined) {
    if (!isPasscode(b.currentPasscode) || !checkPasscode(profile, b.currentPasscode)) {
      return send(res, 403, { error: "Your current passcode isn't right." });
    }
    if (!isPasscode(b.newPasscode)) return send(res, 400, { error: 'The new passcode must be exactly 4 digits.' });
    const { salt, hash } = hashPasscode(b.newPasscode);
    Object.assign(profile, { salt, passcodeHash: hash, sessionVersion: (profile.sessionVersion || 0) + 1 });
    token = issueSession(profile);
  }

  await saveProfile(profile);
  const conflicts = profile.paused ? [] : findConflicts(profile.schedule, claimsOf(all, profile.id));
  return send(res, 200, { profile: toClient(profile), conflicts, ...(token ? { token } : {}) });
});
