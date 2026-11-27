import { handler, method, send } from '../lib/http.js';
import { readJson } from '../lib/store.js';

// Public health of the nightly booker: when it last ran and whether the
// trigger is working. No profile data.
export default handler(async (req, res) => {
  if (!method(req, res, ['GET'])) return;
  const [run, dispatch] = await Promise.all([readJson('meta/last-run.json'), readJson('meta/last-dispatch.json')]);
  return send(res, 200, { lastRun: run?.data || null, lastDispatch: dispatch?.data || null });
});
