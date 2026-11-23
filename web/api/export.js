import { handler, method, send } from '../lib/http.js';
import { listProfiles } from '../lib/store.js';
import { isSyncRequest } from '../lib/auth.js';
import { toExport } from '../lib/profiles.js';

// Used only by the nightly GitHub Action. Profiles come back in creation order,
// which is the order the booker processes them in.
export default handler(async (req, res) => {
  if (!method(req, res, ['GET'])) return;
  if (!isSyncRequest(req)) return send(res, 401, { error: 'Unauthorized.' });
  const all = await listProfiles();
  return send(res, 200, {
    generatedAt: new Date().toISOString(),
    profiles: all.filter((p) => !p.paused).map(toExport),
  });
});
