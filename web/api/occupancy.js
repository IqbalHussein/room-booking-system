import { handler, method, send } from '../lib/http.js';
import { listProfiles } from '../lib/store.js';
import { readSession } from '../lib/auth.js';
import { claimsOf } from '../lib/schedule.js';

export default handler(async (req, res) => {
  if (!method(req, res, ['GET'])) return;
  const session = readSession(req);
  if (!session) return send(res, 401, { error: 'Your session ended. Enter your passcode again.' });
  const all = await listProfiles();
  return send(res, 200, { claims: claimsOf(all, session.pid) });
});
